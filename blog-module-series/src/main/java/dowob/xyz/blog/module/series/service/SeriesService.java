package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.common.api.dto.AuthorSummary;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.module.series.exception.SeriesErrorCode;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.response.MyProgress;
import dowob.xyz.blog.module.series.model.dto.response.SeriesDetailResponse;
import dowob.xyz.blog.module.series.model.dto.response.SeriesSummaryResponse;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Series Service — CRUD（create / update / delete）與詳情查詢。
 *
 * <p>M4-b 起本類別只以 <b>UUID</b> 識別使用者與文章，且不 import 任何其他模組的類別：</p>
 * <ul>
 *   <li>授權：以 {@code AuthUser.uuid()} 比對 {@code series.author_uuid} 與 {@code ArticleData.authorUuid}；</li>
 *   <li>作者資訊：一頁內的作者 UUID 去重後，只呼叫 {@link UserFacade#getDisplayInfoByUuids} 一次
 *       （不跨表連結使用者資料）；查無該使用者時作者欄位皆為 null，與先前外連結（LEFT JOIN）無匹配相同；</li>
 *   <li>文章：全部走 {@link ArticleFacade} 的 UUID 版（摘要用 {@code getSummariesByUuids}，
 *       不再注入 article 模組的 {@code ArticleQueryService}）；</li>
 *   <li>併發：加入／移出以條件式寫入的 affected rows 決定計數是否變動（RACE-14）；
 *       slug 唯一性由資料庫約束決定（RACE-15）；</li>
 *   <li>刪除：交易 commit 後才發 {@code SeriesDeletedEvent}（{@code code-standards.md}「Transaction + MQ」）。</li>
 * </ul>
 *
 * @author Yuan
 * @version 2.0
 */
@Service
@RequiredArgsConstructor
public class SeriesService {

    /** Series 資料庫存取（自己的表） */
    private final SeriesRepository repo;

    /** Series MyBatis Mapper（自己的表：寫入、批次讀取、反正規化計數） */
    private final SeriesMapper mapper;

    /** 文章跨模組 facade（只用 UUID 版方法） */
    private final ArticleFacade articleFacade;

    /** 閱讀跨模組 facade（我的閱讀進度） */
    private final ReadingFacade readingFacade;

    /** 使用者跨模組 facade（批次解析作者顯示資訊） */
    private final UserFacade userFacade;

    /** 程式化交易：刪除時把 DB 操作收斂在交易內，commit 後才發事件 */
    private final TransactionTemplate transactionTemplate;

    /** 事件發布元件（best-effort，commit 後呼叫） */
    private final SeriesEventPublisher eventPublisher;

    /** 公開列表每頁筆數上界，避免未認證請求觸發無上界查詢。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 閱讀進度達到此值視為已讀完 */
    private static final BigDecimal READ_THRESHOLD = new BigDecimal("0.95");

    /**
     * 條件式加入 series 的最多嘗試次數。
     *
     * <p>第一次回 false 且重讀發現文章「無 series」，代表它在兩次讀寫之間被另一個請求移出了；
     * 再試一次即可。仍失敗就放棄（回報文章屬於其他 series），不無限重試。</p>
     */
    private static final int MAX_ASSIGN_ATTEMPTS = 2;

    /**
     * 列出公開的 Series 列表（至少含一篇 PUBLISHED 文章），支援分頁。
     *
     * <p>page 最小 1、size 夾在 [1, {@value #MAX_PAGE_SIZE}]，避免匿名端點被超大 size 放大查詢。</p>
     *
     * <p>
     * 三段式流程（消除最後 3 處直讀 articles，ARCH-13）：
     * <ol>
     *   <li>{@code mapper.findAllUuidsOrderByCreatedAtDesc()} 取全部 series 的 UUID（自己的表，series
     *       為低基數實體），最新優先；</li>
     *   <li>{@code articleFacade.countPublishedBySeriesUuids} 一次取得可見性過濾條件（key 集合，
     *       只含 count &gt; 0 者）與即時 PUBLISHED 計數（value）——集合述詞，見 {@code architecture.md}；</li>
     *   <li>對已過濾且已排序的 UUID 取該頁 sublist，才呼叫 {@code mapper.findByUuids} 查明細，
     *       再以 {@link UserFacade#getDisplayInfoByUuids} <b>一次</b>解析整頁的作者。</li>
     * </ol>
     * {@code total} 即第 2 步過濾後的可見集合大小，不再需要獨立 COUNT 查詢；
     * {@code articleCount} 為即時 PUBLISHED 計數，不沿用 {@code series.article_count}
     * 這個含非公開文章的反正規化欄位。
     * </p>
     *
     * @param page 當前頁碼（1-based）
     * @param size 每頁筆數
     * @return 分頁結果
     */
    @Transactional(readOnly = true)
    public PageResult<SeriesSummaryResponse> listPublic(int page, int size) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        List<UUID> allUuids = mapper.findAllUuidsOrderByCreatedAtDesc();
        if (allUuids.isEmpty()) {
            return PageResult.of(page, size, 0L, List.of());
        }

        Map<UUID, Integer> publishedCounts = articleFacade.countPublishedBySeriesUuids(allUuids);
        List<UUID> visibleUuids = allUuids.stream()
                .filter(publishedCounts::containsKey)
                .toList();

        long total = visibleUuids.size();
        /*
         * page 為 client 給的 int，(page - 1) * size 以 int 相乘在大 page 時會溢位成負數；
         * 舊寫法 Math.max(0, …) 會把溢位的負值吞成 0，等於靜默地回第 1 頁內容，卻仍回報呼叫端
         * 給的巨大 page 號，與姊妹端點 BookmarkQueryService 對同樣輸入的行為（回空清單）不一致。
         * (long) 必須放在第一個運算元上，讓整個乘法以 long 運算，避免相乘當下就已溢位。
         */
        long offset = (long) (page - 1) * size;
        if (offset >= visibleUuids.size()) {
            return PageResult.of(page, size, total, List.of());
        }
        int from = (int) offset;
        List<UUID> pageUuids = visibleUuids.subList(from, Math.min(from + size, visibleUuids.size()));

        /*
         * pageUuids 的大小以本方法開頭的 MAX_PAGE_SIZE 為界（100），低於
         * BatchedQuery.BATCH_SIZE（500），故此處的 IN (...) 不需切批——
         * 界限由該常數保證，不是靠呼叫端自律。
         */
        Map<UUID, Series> rowByUuid = mapper.findByUuids(pageUuids).stream()
                .collect(Collectors.toMap(Series::getUuid, r -> r));
        List<Series> pageRows = pageUuids.stream()
                .map(rowByUuid::get)
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, UserDisplayInfo> authors = resolveAuthors(pageRows);

        List<SeriesSummaryResponse> records = pageRows.stream()
                .map(row -> {
                    SeriesSummaryResponse r = toSummaryResponse(row, authors);
                    r.setArticleCount(publishedCounts.getOrDefault(row.getUuid(), 0));
                    return r;
                })
                .toList();

        return PageResult.of(page, size, total, records);
    }

    /**
     * 一次解析多個 series 的作者顯示資訊。
     *
     * <p>作者 UUID 去重並忽略 null 後，<b>只呼叫 {@link UserFacade#getDisplayInfoByUuids} 一次</b>；
     * 沒有任何作者要查時不呼叫。不依使用者狀態過濾（停權、已刪除者仍回傳其暱稱與頭像）。</p>
     *
     * @param rows series 列
     * @return 作者 UUID → 顯示資訊；查無者不在結果中
     */
    private Map<UUID, UserDisplayInfo> resolveAuthors(Collection<Series> rows) {
        Set<UUID> authorUuids = rows.stream()
                .map(Series::getAuthorUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (authorUuids.isEmpty()) {
            return Map.of();
        }
        return userFacade.getDisplayInfoByUuids(authorUuids);
    }

    /**
     * 組出作者摘要；查無該使用者時三個欄位皆為 null（與舊的外連結無匹配相同）。
     *
     * @param authorUuid 作者公開 UUID
     * @param authors    已解析的作者顯示資訊
     * @return 作者摘要（永不為 null）
     */
    private AuthorSummary toAuthorSummary(UUID authorUuid, Map<UUID, UserDisplayInfo> authors) {
        UserDisplayInfo info = authorUuid == null ? null : authors.get(authorUuid);
        return info == null
                ? new AuthorSummary(null, null, null)
                : new AuthorSummary(info.uuid(), info.nickname(), info.avatarUrl());
    }

    private SeriesSummaryResponse toSummaryResponse(Series row, Map<UUID, UserDisplayInfo> authors) {
        SeriesSummaryResponse r = new SeriesSummaryResponse();
        r.setUuid(row.getUuid());
        r.setTitle(row.getTitle());
        r.setSlug(row.getSlug());
        r.setDescription(row.getDescription());
        r.setCoverImageUrl(row.getCoverImageUrl());
        r.setArticleCount(row.getArticleCount());
        r.setCreatedAt(row.getCreatedAt());
        r.setUpdatedAt(row.getUpdatedAt());
        r.setAuthor(toAuthorSummary(row.getAuthorUuid(), authors));
        return r;
    }

    /**
     * 檢查操作者是否為 series 的擁有者或管理員，否則拋 {@code SERIES_ACCESS_DENIED}。
     *
     * @param series   series 實體
     * @param userUuid 操作者公開 UUID
     * @param isAdmin  操作者是否為管理員
     */
    private void requireOwnerOrAdmin(Series series, UUID userUuid, boolean isAdmin) {
        if (!isAdmin && !(userUuid != null && userUuid.equals(series.getAuthorUuid()))) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }
    }

    /**
     * 建立 Series。
     *
     * <p>
     * 回傳 {@link SeriesSummaryResponse} 而非 Series entity：entity 帶內部 {@code Long id}，
     * 違反 {@code architecture.md}「All external IDs must be UUIDs」。
     * 採既有的 summary 形狀而非新增 DTO，使 create / update / list 回應一致（作者一律以
     * {@code AuthorSummary} 呈現，含 nickname 與 avatar）。代價為寫入後多一次 re-fetch。
     * </p>
     *
     * <p><b>RACE-15</b>：slug 唯一性不再以 {@code existsBySlug} 先查再寫（兩個請求同時通過檢查、
     * 其中一個撞 UNIQUE 變成 500），改為 {@code INSERT ... ON CONFLICT (slug) DO NOTHING}，
     * affected = 0 即 {@code SLUG_ALREADY_USED}。</p>
     *
     * @param userUuid 建立者（即作者）公開 UUID
     * @param req      建立請求
     * @return 建立後的 Series summary（對外只出 UUID）
     * @throws BusinessException {@code S0104} slug 已被使用
     */
    @Transactional
    public SeriesSummaryResponse createSeries(UUID userUuid, CreateSeriesRequest req) {
        Series s = new Series();
        s.setUuid(UUID.randomUUID());
        s.setTitle(req.getTitle());
        s.setSlug(req.getSlug());
        s.setDescription(req.getDescription());
        s.setCoverImageUrl(req.getCoverImageUrl());
        s.setAuthorUuid(userUuid);
        s.setArticleCount(0);
        if (mapper.insertIfSlugAbsent(s) == 0) {
            throw new BusinessException(SeriesErrorCode.SLUG_ALREADY_USED);
        }
        Series saved = repo.findByUuid(s.getUuid())
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        return toSummaryResponse(saved, resolveAuthors(List.of(saved)));
    }

    /**
     * 更新 Series（需 ownership 或 admin）。
     *
     * <p>回傳 {@link SeriesSummaryResponse} 而非 entity，理由見 {@link #createSeries}。</p>
     *
     * <p><b>RACE-15</b>：以<b>單一</b> UPDATE 寫入（只更新非 null 欄位），slug 衝突由資料庫唯一約束決定，
     * 捕捉 {@link DuplicateKeyException} 轉成 {@code SLUG_ALREADY_USED}。PostgreSQL 的交易在錯誤後即中止，
     * 所以捕捉後<b>立刻拋出、不再執行任何 SQL</b>（不 re-fetch、不查作者）；也因此不能以
     * 「try save、catch 後繼續」的方式處理。</p>
     *
     * @param seriesUuid Series 公開 UUID
     * @param userUuid   操作者公開 UUID
     * @param isAdmin    是否為管理員
     * @param req        更新請求（僅非 null 欄位生效）
     * @return 更新後的 Series summary（對外只出 UUID）
     * @throws BusinessException {@code S0101} 不存在、{@code S0102} 無權限、{@code S0104} slug 已被使用
     */
    @Transactional
    public SeriesSummaryResponse updateSeries(UUID seriesUuid, UUID userUuid, boolean isAdmin, UpdateSeriesRequest req) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        requireOwnerOrAdmin(s, userUuid, isAdmin);
        try {
            mapper.updateBasicFields(s.getId(), req.getTitle(), req.getSlug(),
                    req.getDescription(), req.getCoverImageUrl());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(SeriesErrorCode.SLUG_ALREADY_USED);
        }
        Series updated = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        return toSummaryResponse(updated, resolveAuthors(List.of(updated)));
    }

    /**
     * 刪除 Series（需 ownership 或 admin），commit 後發送 {@code SeriesDeletedEvent}。
     *
     * <p>文章脫離系列由資料庫的 {@code articles.series_id → series(id) ON DELETE SET NULL} 負責（M6 才改為
     * 由 article 訂閱事件處理），語意不變。</p>
     *
     * <p><b>刻意不標註 {@code @Transactional}</b>：DB 操作以 {@link TransactionTemplate} 收斂為單一交易，
     * commit 成功後才 best-effort 發事件（{@code code-standards.md}「Transaction + MQ 時序」）。
     * 若整個方法在交易內，事件會在 commit 前發出，commit 失敗時就留下「MQ 說刪了、DB 說沒刪」的不一致。
     * 權限不足或 series 不存在時交易 rollback、不發事件。</p>
     *
     * @param seriesUuid Series 公開 UUID
     * @param userUuid   操作者公開 UUID
     * @param isAdmin    是否為管理員
     * @throws BusinessException {@code S0101} 不存在、{@code S0102} 無權限
     */
    public void deleteSeries(UUID seriesUuid, UUID userUuid, boolean isAdmin) {
        transactionTemplate.executeWithoutResult(status -> {
            Series s = repo.findByUuid(seriesUuid)
                    .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
            requireOwnerOrAdmin(s, userUuid, isAdmin);
            repo.deleteById(s.getId());
        });
        eventPublisher.publishDeleted(seriesUuid);
    }

    /**
     * 將文章加入 Series（或更新排序位置）。
     *
     * <p>業務規則：
     * <ul>
     *   <li>文章必須為 PUBLISHED 狀態（S0103）</li>
     *   <li>非 admin 只能加入自己的文章（S0102，以 {@code authorUuid} 比對）</li>
     *   <li>文章若已屬於另一個 Series 則拒絕（S0106）</li>
     *   <li>文章已在同一個 Series 中時，僅更新位置，不重複 +1 計數</li>
     * </ul>
     * </p>
     *
     * <p><b>RACE-14</b>：「是否為新成員」不再由先前讀到的 {@code seriesUuid} 推論（check-then-act：
     * 兩個請求同時加入同一篇文章，都讀到「無 series」，都 +1），而是由寫入本身決定——
     * {@link ArticleFacade#assignToSeriesIfUnassigned} 回傳 true 才 {@code article_count + 1}。
     * 回傳 false 代表文章已屬於某個 series（含同一個，或剛被另一個請求加入），此時重讀判斷：
     * 同一個 series 只更新位置；其他 series 回 S0106。</p>
     *
     * @param seriesUuid  Series 公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param userUuid    操作者公開 UUID
     * @param isAdmin     是否管理員
     * @param position    在 Series 中的排序位置
     */
    @Transactional
    public void addArticleToSeries(UUID seriesUuid, UUID articleUuid, UUID userUuid, boolean isAdmin, int position) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        requireOwnerOrAdmin(s, userUuid, isAdmin);

        ArticleData article = articleFacade.findByUuid(articleUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.ARTICLE_NOT_FOUND));
        if (!ArticleStatus.PUBLISHED.name().equals(article.status())) {
            throw new BusinessException(SeriesErrorCode.ARTICLE_NOT_PUBLISHED);
        }
        if (!isAdmin && !(userUuid != null && userUuid.equals(article.authorUuid()))) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }
        if (article.seriesUuid() != null && !article.seriesUuid().equals(s.getUuid())) {
            throw new BusinessException(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES);
        }

        for (int attempt = 1; attempt <= MAX_ASSIGN_ATTEMPTS; attempt++) {
            if (articleFacade.assignToSeriesIfUnassigned(articleUuid, s.getUuid(), position)) {
                mapper.incrementArticleCount(s.getId());
                return;
            }
            ArticleData current = articleFacade.findByUuid(articleUuid)
                    .orElseThrow(() -> new BusinessException(SeriesErrorCode.ARTICLE_NOT_FOUND));
            if (current.seriesUuid() == null) {
                /* 兩次讀寫之間被另一個請求移出了：再試一次條件式指派 */
                continue;
            }
            if (!current.seriesUuid().equals(s.getUuid())) {
                throw new BusinessException(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES);
            }
            articleFacade.updateSeriesAssignment(articleUuid, s.getUuid(), position);
            return;
        }
        throw new BusinessException(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES);
    }

    /**
     * 將文章從 Series 移除。
     *
     * <p>文章不屬於此 Series 時拋出 S0105。</p>
     *
     * <p><b>RACE-14</b>：移出以 {@link ArticleFacade#removeFromSeriesIfAssigned} 的 affected rows 決定是否
     * {@code article_count - 1}。兩個請求同時移出同一篇文章，只有一個得到 true；另一個與依序第二次移出相同，
     * 得到 S0105，計數不會被多扣。</p>
     *
     * @param seriesUuid  Series 公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param userUuid    操作者公開 UUID
     * @param isAdmin     是否管理員
     */
    @Transactional
    public void removeArticleFromSeries(UUID seriesUuid, UUID articleUuid, UUID userUuid, boolean isAdmin) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        requireOwnerOrAdmin(s, userUuid, isAdmin);

        ArticleData article = articleFacade.findByUuid(articleUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.ARTICLE_NOT_FOUND));
        if (!s.getUuid().equals(article.seriesUuid())) {
            throw new BusinessException(SeriesErrorCode.ARTICLE_NOT_IN_SERIES);
        }

        if (!articleFacade.removeFromSeriesIfAssigned(articleUuid, s.getUuid())) {
            throw new BusinessException(SeriesErrorCode.ARTICLE_NOT_IN_SERIES);
        }
        mapper.decrementArticleCount(s.getId());
    }

    /**
     * 取得 Series 詳情（含文章列表與我的進度）。
     *
     * <p>
     * articles sub-list 透過 {@code ArticleFacade.getSummariesByUuids} 取得 enrich 後的 summary
     * （含作者與標籤）。myProgress 僅在 currentUserUuid != null（已登入）時計算；
     * progress &gt;= 0.95 視為已讀完。
     * </p>
     *
     * <p>
     * 本端點為對外讀取用途，<b>不論匿名或任何登入者一律只暴露 PUBLISHED 文章</b>（含系列作者本人
     * ——作者要看自己的草稿走管理端點，此處不做作者分支）。ArticleFacade 的 SP-B read 依契約
     * 不限狀態（見 ArticleFacade javadoc「caller 自行依 status 判斷」），過濾責任在此。
     * DRAFT / REJECTED / ARCHIVED / PENDING_REVIEW 皆非公開可見，故採白名單而非排除 DRAFT。
     * 過濾後的列表同時餵給 toDetailResponse 與 myProgress，確保文章列表、articleCount 與進度分母口徑一致
     * （articleCount 亦取過濾後大小，不沿用 series.article_count 這個含非公開文章的非正規化計數）。
     * </p>
     *
     * <p>
     * series 一旦存在即為公開實體：即使目前<b>無任何 PUBLISHED 文章</b>，仍正常回應且 articles 為空清單、
     * articleCount 為 0。公開列表 {@code listPublic} 則另以「有無 PUBLISHED」策展，故此類 series 不進列表
     * （列表策展與詳情存在性刻意分離）。
     * </p>
     *
     * @param slug            Series URL slug
     * @param currentUserUuid 當前使用者公開 UUID（未登入為 null）；僅用於查閱讀進度
     * @return Series 詳情 response
     * @throws BusinessException {@code S0101} 當 slug 對應的 series 不存在
     */
    @Transactional(readOnly = true)
    public SeriesDetailResponse getSeriesDetail(String slug, UUID currentUserUuid) {
        Series row = repo.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));

        List<ArticleData> articles = articleFacade.findBySeriesUuidOrderByPosition(row.getUuid()).stream()
                .filter(a -> ArticleStatus.isPubliclyVisible(a.status()))
                .toList();

        SeriesDetailResponse resp = toDetailResponse(row, articles);

        if (currentUserUuid != null) {
            List<UUID> articleUuids = articles.stream().map(ArticleData::uuid).toList();
            Map<UUID, BigDecimal> progressMap = readingFacade.batchGetProgress(currentUserUuid, articleUuids);

            int readCount = (int) progressMap.values().stream()
                    .filter(p -> p.compareTo(READ_THRESHOLD) >= 0).count();

            UUID nextUnread = articles.stream()
                    .filter(a -> {
                        BigDecimal p = progressMap.get(a.uuid());
                        return p == null || p.compareTo(READ_THRESHOLD) < 0;
                    })
                    .map(ArticleData::uuid)
                    .findFirst().orElse(null);

            resp.setMyProgress(new MyProgress(readCount, articles.size(), nextUnread));
        }
        return resp;
    }

    /**
     * 將 series 列與已過濾的文章轉為 SeriesDetailResponse。
     *
     * <p>articles sub-list 透過 {@code ArticleFacade.getSummariesByUuids} 取得，並以本 series 的 uuid 與標題
     * 覆寫 seriesUuid / seriesTitle 欄位（前端可在點進文章詳情時再查完整 seriesNav，避免 N+1 問題）。
     * 沒有任何公開文章時不呼叫 facade。</p>
     *
     * @param row      series 列
     * @param articles 已過濾為對外可見的文章
     * @return 詳情 response（尚未填 myProgress）
     */
    private SeriesDetailResponse toDetailResponse(Series row, List<ArticleData> articles) {
        SeriesDetailResponse r = new SeriesDetailResponse();
        r.setUuid(row.getUuid());
        r.setTitle(row.getTitle());
        r.setSlug(row.getSlug());
        r.setDescription(row.getDescription());
        r.setCoverImageUrl(row.getCoverImageUrl());
        /*
         * articleCount 對齊已過濾的對外可見列表，與回傳的 articles 清單口徑一致；不用
         * row.getArticleCount()（非正規化計數，含 DRAFT/REJECTED/ARCHIVED/PENDING_REVIEW，
         * 直接沿用會出現「count=N 但只列 M 篇」並反推出隱藏文章數）。
         */
        r.setArticleCount(articles.size());
        r.setCreatedAt(row.getCreatedAt());
        r.setUpdatedAt(row.getUpdatedAt());
        r.setAuthor(toAuthorSummary(row.getAuthorUuid(), resolveAuthors(List.of(row))));

        List<UUID> articleUuids = articles.stream().map(ArticleData::uuid).toList();
        List<ArticleSummaryResponse> summaries = articleUuids.isEmpty()
                ? List.of()
                : articleFacade.getSummariesByUuids(articleUuids);
        summaries.forEach(s -> {
            s.setSeriesUuid(row.getUuid());
            s.setSeriesTitle(row.getTitle());
        });
        r.setArticles(summaries);
        return r;
    }
}
