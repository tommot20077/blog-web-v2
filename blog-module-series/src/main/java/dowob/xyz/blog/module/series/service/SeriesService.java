package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.common.api.dto.AuthorSummary;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import dowob.xyz.blog.module.series.exception.SeriesErrorCode;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.SeriesWithAuthor;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.response.MyProgress;
import dowob.xyz.blog.module.series.model.dto.response.SeriesDetailResponse;
import dowob.xyz.blog.module.series.model.dto.response.SeriesSummaryResponse;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Series Service — CRUD（create / update / delete）與詳情查詢。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class SeriesService {

    private final SeriesRepository repo;
    private final SeriesMapper mapper;
    private final ArticleFacade articleFacade;
    private final ArticleQueryService articleQueryService;  // SP-X: ArticleQueryService 跨模組 inject 議題（spec §9）
    private final ReadingFacade readingFacade;

    /** 公開列表每頁筆數上界，避免未認證請求觸發無上界查詢。 */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 列出公開的 Series 列表（至少含一篇 PUBLISHED 文章），支援分頁。
     *
     * <p>page 最小 1、size 夾在 [1, {@value #MAX_PAGE_SIZE}]，避免匿名端點被超大 size 放大查詢。</p>
     *
     * @param page 當前頁碼（1-based）
     * @param size 每頁筆數
     * @return 分頁結果
     */
    @Transactional(readOnly = true)
    public PageResult<SeriesSummaryResponse> listPublic(int page, int size) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = Math.max(0, (page - 1) * size);
        List<SeriesWithAuthor> rows = mapper.findPublic(size, offset);
        long total = mapper.countPublic();
        List<SeriesSummaryResponse> records = rows.stream().map(this::toSummaryResponse).toList();
        return PageResult.of(page, size, total, records);
    }

    private SeriesSummaryResponse toSummaryResponse(SeriesWithAuthor row) {
        SeriesSummaryResponse r = new SeriesSummaryResponse();
        r.setUuid(row.getUuid());
        r.setTitle(row.getTitle());
        r.setSlug(row.getSlug());
        r.setDescription(row.getDescription());
        r.setCoverImageUrl(row.getCoverImageUrl());
        r.setArticleCount(row.getArticleCount());
        r.setCreatedAt(row.getCreatedAt());
        r.setUpdatedAt(row.getUpdatedAt());
        r.setAuthor(new AuthorSummary(
                row.getAuthorUuid(), row.getAuthorNickname(), row.getAuthorAvatarUrl()));
        return r;
    }

    @Transactional
    public Series createSeries(Long userId, CreateSeriesRequest req) {
        if (repo.existsBySlug(req.getSlug())) {
            throw new BusinessException(SeriesErrorCode.SLUG_ALREADY_USED);
        }
        Series s = new Series();
        s.setUuid(UUID.randomUUID());
        s.setTitle(req.getTitle());
        s.setSlug(req.getSlug());
        s.setDescription(req.getDescription());
        s.setCoverImageUrl(req.getCoverImageUrl());
        s.setAuthorId(userId);
        s.setArticleCount(0);
        return repo.save(s);
    }

    @Transactional
    public Series updateSeries(UUID seriesUuid, Long userId, boolean isAdmin, UpdateSeriesRequest req) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        if (!isAdmin && !s.getAuthorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }
        if (req.getTitle() != null) s.setTitle(req.getTitle());
        if (req.getSlug() != null && !req.getSlug().equals(s.getSlug())) {
            if (repo.existsBySlug(req.getSlug())) {
                throw new BusinessException(SeriesErrorCode.SLUG_ALREADY_USED);
            }
            s.setSlug(req.getSlug());
        }
        if (req.getDescription() != null) s.setDescription(req.getDescription());
        if (req.getCoverImageUrl() != null) s.setCoverImageUrl(req.getCoverImageUrl());
        return repo.save(s);
    }

    @Transactional
    public void deleteSeries(UUID seriesUuid, Long userId, boolean isAdmin) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        if (!isAdmin && !s.getAuthorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }
        repo.deleteById(s.getId());
    }

    /**
     * 將文章加入 Series（或更新排序位置）。
     *
     * <p>業務規則：
     * <ul>
     *   <li>文章必須為 PUBLISHED 狀態（S0103）</li>
     *   <li>文章若已屬於另一個 Series 則拒絕（S0106）</li>
     *   <li>文章已在同一個 Series 中時，僅更新位置，不重複 +1 計數</li>
     * </ul>
     * </p>
     *
     * @param seriesUuid  Series 公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param userId      操作者 ID
     * @param isAdmin     是否管理員
     * @param position    在 Series 中的排序位置
     */
    @Transactional
    public void addArticleToSeries(UUID seriesUuid, UUID articleUuid, Long userId, boolean isAdmin, int position) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        if (!isAdmin && !s.getAuthorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }

        ArticleData article = articleFacade.findByUuid(articleUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.ARTICLE_NOT_FOUND));
        if (!ArticleStatus.PUBLISHED.name().equals(article.status())) {
            throw new BusinessException(SeriesErrorCode.ARTICLE_NOT_PUBLISHED);
        }
        if (!isAdmin && !article.authorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }
        if (article.seriesId() != null && !article.seriesId().equals(s.getId())) {
            throw new BusinessException(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES);
        }

        boolean isNewMember = (article.seriesId() == null);
        articleFacade.updateSeriesAssignment(article.id(), s.getId(), position);

        if (isNewMember) {
            mapper.incrementArticleCount(s.getId());
        }
    }

    /**
     * 將文章從 Series 移除。
     *
     * <p>文章不屬於此 Series 時拋出 S0105。</p>
     *
     * @param seriesUuid  Series 公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param userId      操作者 ID
     * @param isAdmin     是否管理員
     */
    @Transactional
    public void removeArticleFromSeries(UUID seriesUuid, UUID articleUuid, Long userId, boolean isAdmin) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        if (!isAdmin && !s.getAuthorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }

        ArticleData article = articleFacade.findByUuid(articleUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.ARTICLE_NOT_FOUND));
        if (article.seriesId() == null || !article.seriesId().equals(s.getId())) {
            throw new BusinessException(SeriesErrorCode.ARTICLE_NOT_IN_SERIES);
        }

        articleFacade.updateSeriesAssignment(article.id(), null, null);
        mapper.decrementArticleCount(s.getId());
    }

    /**
     * 取得 Series 詳情（含文章列表與我的進度）。
     *
     * <p>
     * articles sub-list 透過 articleQueryService.getArticleSummariesByIds 取得 enrich 後的 summary
     * （含作者與標籤）。myProgress 僅在 currentUserId != null（已登入）時計算；
     * progress >= 0.95 視為已讀完。
     * </p>
     *
     * <p>
     * 本端點為對外讀取用途，<b>不論匿名或任何登入者一律只暴露 PUBLISHED 文章</b>（含系列作者本人
     * ——作者要看自己的草稿走管理端點，此處不做作者分支）。ArticleFacade 的 SP-B read 依契約
     * 不限狀態（見 ArticleFacade javadoc「caller 自行依 status 判斷」），過濾責任在此。
     * DRAFT / REJECTED / ARCHIVED / PENDING_REVIEW 皆非公開可見，故採白名單而非排除 DRAFT。
     * 過濾後的列表同時餵給 toDetailResponse 與 myProgress，確保文章列表與進度分母口徑一致。
     * </p>
     *
     * <p>
     * series 一旦存在即為公開實體：即使目前<b>無任何 PUBLISHED 文章</b>，仍正常回應且 articles 為空清單、
     * articleCount 為 0。公開列表 {@code findPublic} 則另以「有無 PUBLISHED」策展，故此類 series 不進列表
     * （列表策展與詳情存在性刻意分離）。
     * </p>
     *
     * @param slug          Series URL slug
     * @param currentUserId 當前使用者 ID（未登入為 null）
     * @return Series 詳情 response
     * @throws BusinessException {@code S0101} 當 slug 對應的 series 不存在
     */
    @Transactional(readOnly = true)
    public SeriesDetailResponse getSeriesDetail(String slug, Long currentUserId) {
        SeriesWithAuthor row = mapper.findBySlugWithAuthor(slug);
        if (row == null) {
            throw new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND);
        }

        List<ArticleData> articles = articleFacade.findBySeriesIdOrderByPosition(row.getId()).stream()
                .filter(a -> ArticleStatus.PUBLISHED.name().equals(a.status()))
                .toList();

        SeriesDetailResponse resp = toDetailResponse(row, articles);

        if (currentUserId != null) {
            List<Long> articleIds = articles.stream().map(ArticleData::id).toList();
            Map<Long, BigDecimal> progressMap = readingFacade.batchGetProgress(currentUserId, articleIds);

            BigDecimal threshold = new BigDecimal("0.95");
            int readCount = (int) progressMap.values().stream()
                    .filter(p -> p.compareTo(threshold) >= 0).count();

            UUID nextUnread = articles.stream()
                    .filter(a -> {
                        BigDecimal p = progressMap.get(a.id());
                        return p == null || p.compareTo(threshold) < 0;
                    })
                    .map(ArticleData::uuid)
                    .findFirst().orElse(null);

            resp.setMyProgress(new MyProgress(readCount, articles.size(), nextUnread));
        }
        return resp;
    }

    /**
     * 將 SeriesWithAuthor row 轉為 SeriesDetailResponse。
     *
     * <p>articles sub-list 透過 articleQueryService.getArticleSummariesByIds 取得，
     * 並補充 seriesUuid / seriesTitle 欄位（前端可在點進文章詳情時再查完整 seriesNav，
     * 避免 N+1 問題）。</p>
     */
    private SeriesDetailResponse toDetailResponse(SeriesWithAuthor row, List<ArticleData> articles) {
        SeriesDetailResponse r = new SeriesDetailResponse();
        r.setUuid(row.getUuid());
        r.setTitle(row.getTitle());
        r.setSlug(row.getSlug());
        r.setDescription(row.getDescription());
        r.setCoverImageUrl(row.getCoverImageUrl());
        // 只計已過濾的 PUBLISHED 文章數，與回傳的 articles 清單口徑一致；
        // 不用 row.getArticleCount()（反正規化欄，含非 PUBLISHED 且會漂移）。
        r.setArticleCount(articles.size());
        r.setCreatedAt(row.getCreatedAt());
        r.setUpdatedAt(row.getUpdatedAt());
        r.setAuthor(new AuthorSummary(
                row.getAuthorUuid(), row.getAuthorNickname(), row.getAuthorAvatarUrl()));

        // 取得 articles sub-list：以 id 列表批次查詢，再補 series 三欄
        // SP-X: ArticleQueryService 跨模組 inject 議題（spec §9），同 BookmarkController pattern
        List<Long> articleIds = articles.stream().map(ArticleData::id).toList();
        List<ArticleSummaryResponse> summaries = articleIds.isEmpty()
                ? List.of()
                : articleQueryService.getArticleSummariesByIds(articleIds);
        summaries.forEach(s -> {
            s.setSeriesUuid(row.getUuid());
            s.setSeriesTitle(row.getTitle());
        });
        r.setArticles(summaries);
        return r;
    }
}
