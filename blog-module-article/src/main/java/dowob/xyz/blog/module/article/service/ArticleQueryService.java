package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.response.ArticleArchiveResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文章查詢服務（CQRS Read 層）。
 *
 * <p>對 Controller 提供文章讀取入口；委派 {@link ArticleService} 取得原始資料，
 * 再以 {@link dowob.xyz.blog.infrastructure.facade.ReadingFacade} 補上當前使用者的 liked / bookmarked / progress 狀態，
 * 並以 {@link dowob.xyz.blog.infrastructure.facade.SeriesFacade} 填充 series 導覽資訊（seriesNav）。</p>
 *
 * <p>採此分層的原因：避免 ArticleServiceImpl 與其他 Service 形成循環依賴。
 * 將「Read + 使用者狀態組裝」與「Write + 計數維護」分開，符合 CQRS-lite 慣例。</p>
 *
 * <p>ArticleSummaryResponse 未暴露 DB 主鍵（id），故透過 {@link ArticleMapper#findIdsByUuids}
 * 批量查詢 UUID→id，避免 N+1 問題。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class ArticleQueryService {

    private final ArticleService articleService;
    private final ArticleMapper articleMapper;
    private final ReadingFacade readingFacade;
    private final SeriesFacade seriesFacade;

    // ─── 列表查詢（帶 liked 填充）───

    /**
     * 分頁取得已發布文章列表（含 liked 狀態）。
     *
     * @param page 頁碼（從 1 開始）
     * @param size 每頁筆數
     * @return 分頁文章摘要列表（liked 已填充）
     */
    public PageResult<ArticleSummaryResponse> getPublishedArticles(int page, int size) {
        PageResult<ArticleSummaryResponse> result = articleService.getPublishedArticles(page, size);
        enrich(result.getRecords());
        return result;
    }

    /**
     * 根據分類 slug 分頁取得已發布文章列表（含 liked 狀態）。
     *
     * @param categorySlug 分類 slug
     * @param page         頁碼（從 1 開始）
     * @param size         每頁筆數
     * @return 分頁文章摘要列表（liked 已填充）
     */
    public PageResult<ArticleSummaryResponse> getPublishedArticlesByCategorySlug(
            String categorySlug, int page, int size) {
        PageResult<ArticleSummaryResponse> result =
                articleService.getPublishedArticlesByCategorySlug(categorySlug, page, size);
        enrich(result.getRecords());
        return result;
    }

    /**
     * 分頁取得當前登入用戶的文章列表（含 liked 狀態）。
     *
     * @param authorId 作者資料庫主鍵
     * @param page     頁碼（從 1 開始）
     * @param size     每頁筆數
     * @param status   文章狀態篩選（null 表示查詢全部）
     * @return 分頁文章摘要列表（liked 已填充）
     */
    public PageResult<ArticleSummaryResponse> getMyArticles(
            Long authorId, int page, int size, ArticleStatus status) {
        PageResult<ArticleSummaryResponse> result =
                articleService.getMyArticles(authorId, page, size, status);
        enrich(result.getRecords());
        return result;
    }

    /**
     * 分頁取得待審文章列表（含 liked 狀態，僅 ADMIN）。
     *
     * @param page 頁碼（從 1 開始）
     * @param size 每頁筆數
     * @return 分頁文章摘要列表（liked 已填充）
     */
    public PageResult<ArticleSummaryResponse> getPendingArticles(int page, int size) {
        PageResult<ArticleSummaryResponse> result = articleService.getPendingArticles(page, size);
        enrich(result.getRecords());
        return result;
    }

    /**
     * 取得全部已發布文章的歸檔精簡投影（供前端年度歸檔頁使用）。
     *
     * <p>唯讀查詢、無分頁：直接委派 {@link ArticleService#getArchive()}。
     * 歸檔投影僅含 uuid / title / slug / publishedAt / tags，無使用者個人化狀態（liked/bookmarked/progress），
     * 故不經 enrich 處理，原樣回傳。結果依 publishedAt 由新到舊排序，無已發布文章時回傳空清單。</p>
     *
     * @return 歸檔文章投影列表（依 publishedAt 由新到舊排序，無資料回傳空清單）
     */
    public List<ArticleArchiveResponse> getArchive() {
        return articleService.getArchive();
    }

    // ─── 單篇查詢（帶 liked 填充）───

    /**
     * 根據 UUID 取得文章詳情（含 liked 狀態）。
     *
     * @param articleUuid 文章公開 UUID
     * @param viewerId    觀看者 ID（匿名為 null）
     * @param viewerRole  觀看者角色（匿名為 null）
     * @param clientIp    客戶端 IP（用於防刷）
     * @return 文章完整資訊（liked 已填充）
     */
    public ArticleResponse getArticleByUuid(
            UUID articleUuid, Long viewerId, Role viewerRole, String clientIp) {
        ArticleResponse resp = articleService.getArticleByUuid(articleUuid, viewerId, viewerRole, clientIp);
        enrichSingle(resp);
        return resp;
    }

    /**
     * 根據 slug 取得文章詳情（含 liked 狀態）。
     *
     * @param slug       文章 URL slug
     * @param viewerId   觀看者 ID（匿名為 null）
     * @param viewerRole 觀看者角色（匿名為 null）
     * @param clientIp   客戶端 IP（用於防刷）
     * @return 文章完整資訊（liked 已填充）
     */
    public ArticleResponse getArticleBySlug(
            String slug, Long viewerId, Role viewerRole, String clientIp) {
        ArticleResponse resp = articleService.getArticleBySlug(slug, viewerId, viewerRole, clientIp);
        enrichSingle(resp);
        return resp;
    }

    // ─── 收藏列表查詢 ───

    /**
     * 根據文章 ID 列表批次取得文章摘要（含 liked 狀態）。
     *
     * <p>供 BookmarkController 使用：先取得摘要，再批次填充 liked 狀態。</p>
     *
     * @param articleIds 文章資料庫主鍵列表
     * @return 文章摘要列表（liked 已填充）
     */
    public List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds) {
        List<ArticleSummaryResponse> records = articleService.getArticleSummariesByIds(articleIds);
        enrich(records);
        return records;
    }

    // ─── 私有 helper ───

    /**
     * 批次填充 ArticleSummaryResponse 列表的 liked / bookmarked / lastReadProgress / seriesUuid / seriesTitle 欄位。
     *
     * <p>ArticleSummaryResponse 未暴露 DB 主鍵，故先透過 UUID 批量查詢取得 id Map，
     * 再以 id 批量查詢各狀態，避免 N+1。未登入時 liked/bookmarked=false，lastReadProgress=null。</p>
     *
     * <p>seriesUuid / seriesTitle 透過 SeriesFacade 補充（對有 seriesPosition 的文章）；
     * seriesPosition 已由 ArticleServiceImpl.toSummaryResponse 填入。</p>
     *
     * @param records ArticleSummaryResponse 列表
     */
    private void enrich(List<ArticleSummaryResponse> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Long userId = currentUserIdOrNull();

        // 批量取得 uuid → id 對應
        List<UUID> uuids = records.stream()
                .map(ArticleSummaryResponse::getUuid)
                .collect(Collectors.toList());
        List<Article> idRows = articleMapper.findIdsByUuids(uuids);
        Map<UUID, Long> uuidToId = idRows.stream()
                .collect(Collectors.toMap(Article::getUuid, Article::getId));

        List<Long> articleIds = uuids.stream()
                .map(uuidToId::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        // 補充 seriesUuid / seriesTitle（批次查詢，避免 N+1）
        Map<Long, SeriesBasicInfo> seriesMap = seriesFacade.batchGetSeriesBasicInfo(articleIds);
        records.forEach(r -> {
            if (r.getSeriesPosition() != null) {
                Long id = uuidToId.get(r.getUuid());
                if (id != null) {
                    SeriesBasicInfo info = seriesMap.get(id);
                    if (info != null) {
                        r.setSeriesUuid(info.getSeriesUuid());
                        r.setSeriesTitle(info.getSeriesTitle());
                    }
                }
            }
        });

        if (userId == null) {
            records.forEach(r -> {
                r.setLiked(false);
                r.setBookmarked(false);
                r.setLastReadProgress(null);
            });
            return;
        }

        Set<Long> likedIds = readingFacade.batchIsLiked(userId, articleIds);
        Set<Long> bookmarkedIds = readingFacade.batchIsBookmarked(userId, articleIds);
        Map<Long, BigDecimal> progressMap = readingFacade.batchGetProgress(userId, articleIds);

        records.forEach(r -> {
            Long id = uuidToId.get(r.getUuid());
            r.setLiked(id != null && likedIds.contains(id));
            r.setBookmarked(id != null && bookmarkedIds.contains(id));
            r.setLastReadProgress(id != null ? progressMap.get(id) : null);
        });
    }

    /**
     * 填充單篇 ArticleResponse 的 liked / bookmarked / lastReadProgress / seriesNav 欄位。
     *
     * <p>ArticleResponse 未暴露 DB 主鍵，透過 UUID 查詢 id，再判斷各狀態。
     * 未登入時 liked/bookmarked=false，lastReadProgress=null；seriesNav 不受登入狀態影響。</p>
     *
     * @param resp ArticleResponse（可為 null）
     */
    private void enrichSingle(ArticleResponse resp) {
        if (resp == null) {
            return;
        }
        Long articleId = articleService.findIdByUuid(resp.getUuid());

        // 填充 series 導覽資訊（不受登入狀態影響）
        if (articleId != null) {
            seriesFacade.getSeriesNavigation(articleId).ifPresent(resp::setSeriesNav);
        }

        Long userId = currentUserIdOrNull();
        if (userId == null) {
            resp.setLiked(false);
            resp.setBookmarked(false);
            resp.setLastReadProgress(null);
            return;
        }
        resp.setLiked(articleId != null && readingFacade.isLiked(userId, articleId));
        resp.setBookmarked(articleId != null && readingFacade.isBookmarked(userId, articleId));
        if (resp.getUuid() != null) {
            resp.setLastReadProgress(readingFacade.getProgress(userId, resp.getUuid()));
        }
    }

    /**
     * 從 SecurityContextHolder 取得當前登入使用者 ID，未登入時回傳 null。
     *
     * <p>Principal 為 Long（JwtAuthenticationFilter 寫入的 userId）。</p>
     *
     * @return 使用者 DB ID，或 null（匿名 / 未認證）
     */
    private Long currentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if ("anonymousUser".equals(principal)) {
            return null;
        }
        if (principal instanceof Long id) {
            return id;
        }
        return null;
    }
}
