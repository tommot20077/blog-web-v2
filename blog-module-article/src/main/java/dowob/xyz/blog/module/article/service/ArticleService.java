package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleArchiveResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;

import java.util.List;
import java.util.UUID;


/**
 * 文章服務介面
 *
 * <p>
 * 定義文章模組的所有業務操作合約。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface ArticleService {

    /**
     * 建立文章
     *
     * @param authorId 作者資料庫主鍵
     * @param request  建立文章請求
     * @return 建立後的 Editor 文章資訊
     */
    EditorArticleResponse createArticle(Long authorId, CreateArticleRequest request);

    /**
     * 更新文章（僅允許 DRAFT 或 REJECTED 狀態）
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     * @param request      更新請求
     * @return 更新後的 Editor 文章資訊
     * @throws org.springframework.web.server.ResponseStatusException HTTP 403 當文章狀態為 PENDING_REVIEW / PUBLISHED / ARCHIVED
     */
    EditorArticleResponse updateArticle(Long operatorId, Role operatorRole, UUID articleUuid, UpdateArticleRequest request);

    /**
     * 取得文章供 Editor 編輯（僅作者本人）
     *
     * @param articleUuid 文章公開 UUID
     * @param requesterId 請求者資料庫主鍵
     * @return Editor 用文章資訊
     * @throws org.springframework.web.server.ResponseStatusException HTTP 403 當請求者非作者
     */
    EditorArticleResponse getArticleForEdit(UUID articleUuid, Long requesterId);

    /**
     * 刪除文章
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     */
    void deleteArticle(Long operatorId, Role operatorRole, UUID articleUuid);

    /**
     * 根據 UUID 取得文章詳情
     *
     * <p>
     * 公開 API：已發布文章任何人可存取；其他狀態需作者本人或 ADMIN 權限。
     * 匿名存取（viewerId/viewerRole 為 null）只能取得 PUBLISHED 文章。
     * clientIp 用於 Redis 防刷計數（同 IP 5 分鐘內只計算一次瀏覽）。
     * </p>
     *
     * @param articleUuid 文章公開 UUID
     * @param viewerId    觀看者 ID（匿名為 null）
     * @param viewerRole  觀看者角色（匿名為 null）
     * @param clientIp    客戶端 IP（用於防刷）
     * @return 文章完整資訊
     */
    ArticleResponse getArticleByUuid(UUID articleUuid, Long viewerId, Role viewerRole, String clientIp);

    /**
     * 分頁取得已發布文章列表（公開）
     *
     * @param page 頁碼（從 1 開始）
     * @param size 每頁筆數
     * @return 分頁文章摘要列表
     */
    PageResult<ArticleSummaryResponse> getPublishedArticles(int page, int size);

    /**
     * 根據分類 slug 分頁取得已發布文章列表
     *
     * @param categorySlug 分類 slug
     * @param page         頁碼
     * @param size         每頁筆數
     * @return 分頁文章摘要列表
     */
    PageResult<ArticleSummaryResponse> getPublishedArticlesByCategorySlug(String categorySlug, int page, int size);

    /**
     * 分頁取得當前登入用戶的文章列表
     *
     * @param authorId 作者資料庫主鍵
     * @param page     頁碼（從 1 開始）
     * @param size     每頁筆數
     * @param status   文章狀態篩選（null 表示查詢全部）
     * @return 分頁文章摘要列表
     */
    PageResult<ArticleSummaryResponse> getMyArticles(Long authorId, int page, int size, ArticleStatus status);

    /**
     * 發布文章
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     * @return 發布後的文章完整資訊
     */
    ArticleResponse publishArticle(Long operatorId, Role operatorRole, UUID articleUuid);

    /**
     * 駁回文章（僅 ADMIN）
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     * @param reason       駁回原因
     * @return 駁回後的文章完整資訊
     */
    ArticleResponse rejectArticle(Long operatorId, Role operatorRole, UUID articleUuid, String reason);

    /**
     * 分頁取得待審文章列表（僅 ADMIN）
     *
     * @param page 頁碼（從 1 開始）
     * @param size 每頁筆數
     * @return 分頁文章摘要列表
     */
    PageResult<ArticleSummaryResponse> getPendingArticles(int page, int size);

    /**
     * 取得全部已發布文章的歸檔精簡投影（供前端年度歸檔頁使用）。
     *
     * <p>
     * 唯讀查詢、無分頁：回傳所有 PUBLISHED 文章的最小欄位（uuid / title / slug / publishedAt / tags），
     * 依 publishedAt 由新到舊排序；草稿、待審、封存等非已發布狀態不納入。無已發布文章時回傳空清單。
     * </p>
     *
     * @return 歸檔文章投影列表（依 publishedAt 由新到舊排序，無資料回傳空清單）
     */
    List<ArticleArchiveResponse> getArchive();

    /**
     * 提交文章審核（DRAFT → PENDING_REVIEW）
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     * @return 提交審核後的文章完整資訊
     */
    ArticleResponse submitForReview(Long operatorId, Role operatorRole, UUID articleUuid);

    /**
     * 根據 slug 取得文章詳情
     *
     * @param slug       文章 URL slug
     * @param viewerId   觀看者 ID（匿名為 null）
     * @param viewerRole 觀看者角色（匿名為 null）
     * @param clientIp   客戶端 IP（用於防刷）
     * @return 文章完整資訊
     */
    ArticleResponse getArticleBySlug(String slug, Long viewerId, Role viewerRole, String clientIp);

    /**
     * 原子性遞增文章留言計數
     *
     * @param articleId 文章資料庫主鍵
     */
    void incrementCommentCount(Long articleId);

    /**
     * 原子性遞減文章留言計數（守衛 > 0，防 underflow）
     *
     * @param articleId 文章資料庫主鍵
     */
    void decrementCommentCount(Long articleId);

    /**
     * 原子性遞增文章按讚計數
     *
     * @param articleId 文章資料庫主鍵
     */
    void incrementLikeCount(Long articleId);

    /**
     * 原子性遞減文章按讚計數（守衛 > 0，防 underflow）
     *
     * @param articleId 文章資料庫主鍵
     */
    void decrementLikeCount(Long articleId);

    /**
     * 根據文章公開 UUID 查詢資料庫主鍵
     *
     * <p>
     * 供跨模組 Service 透過公開 UUID 取得 article PK，避免直接 JOIN articles 表。
     * </p>
     *
     * @param uuid 文章公開 UUID
     * @return 文章資料庫主鍵，若不存在則回傳 null
     */
    Long findIdByUuid(UUID uuid);

    /**
     * 根據文章 ID 列表批次取得文章摘要（供收藏列表等跨模組查詢使用）。
     *
     * <p>Task 12 完整實作；此為 stub，逐筆查詢。</p>
     *
     * @param articleIds 文章資料庫主鍵列表
     * @return 文章摘要列表（依 articleIds 順序）
     */
    List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds);

    /**
     * 根據 ID 列表批次查詢文章實體（供跨模組 uuid 反向映射使用）。
     *
     * @param ids 文章資料庫主鍵列表
     * @return 文章實體列表
     */
    java.util.List<dowob.xyz.blog.module.article.model.Article> findByIds(java.util.List<Long> ids);

    /**
     * 根據文章公開 UUID 查詢文章實體（供跨模組使用，例如 Series 模組）。
     *
     * @param uuid 文章公開 UUID
     * @return 文章 Optional
     */
    java.util.Optional<dowob.xyz.blog.module.article.model.Article> findByUuid(UUID uuid);

    /**
     * 更新文章的 series 歸屬與排序位置（供 Series 模組使用）。
     *
     * <p>傳入 null 表示解除 series 歸屬。</p>
     *
     * @param articleId      文章資料庫主鍵
     * @param seriesId       所屬 series 主鍵（null 表示解除）
     * @param seriesPosition 在 series 中的排序位置（null 表示解除）
     */
    void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition);

    /**
     * 查詢指定 Series 內的所有文章，按 series_position 排序（供 Series 詳情頁使用）。
     *
     * @param seriesId Series 資料庫主鍵
     * @return 按 series_position 升冪排序的文章列表
     */
    java.util.List<dowob.xyz.blog.module.article.model.Article> findBySeriesIdOrderByPosition(Long seriesId);

    /**
     * 根據資料庫主鍵查詢文章實體（供 SeriesFacade 等跨模組使用）。
     *
     * @param id 文章資料庫主鍵
     * @return 文章 Optional
     */
    java.util.Optional<dowob.xyz.blog.module.article.model.Article> findById(Long id);
}
