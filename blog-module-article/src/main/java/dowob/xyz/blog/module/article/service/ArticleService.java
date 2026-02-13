package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;

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
     * @return 建立後的文章完整資訊
     */
    ArticleResponse createArticle(Long authorId, CreateArticleRequest request);

    /**
     * 更新文章
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     * @param request      更新請求
     * @return 更新後的文章完整資訊
     */
    ArticleResponse updateArticle(Long operatorId, Role operatorRole, UUID articleUuid, UpdateArticleRequest request);

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
     * </p>
     *
     * @param articleUuid 文章公開 UUID
     * @param viewerId    觀看者 ID（匿名為 null）
     * @param viewerRole  觀看者角色（匿名為 null）
     * @return 文章完整資訊
     */
    ArticleResponse getArticleByUuid(UUID articleUuid, Long viewerId, Role viewerRole);

    /**
     * 分頁取得已發布文章列表（公開）
     *
     * @param pageNum  頁碼（從 1 開始）
     * @param pageSize 每頁筆數
     * @return 分頁文章摘要列表
     */
    PageResult<ArticleSummaryResponse> getPublishedArticles(int pageNum, int pageSize);

    /**
     * 分頁取得當前登入用戶的文章列表
     *
     * @param authorId 作者資料庫主鍵
     * @param pageNum  頁碼（從 1 開始）
     * @param pageSize 每頁筆數
     * @return 分頁文章摘要列表
     */
    PageResult<ArticleSummaryResponse> getMyArticles(Long authorId, int pageNum, int pageSize);

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
     * @param pageNum  頁碼（從 1 開始）
     * @param pageSize 每頁筆數
     * @return 分頁文章摘要列表
     */
    PageResult<ArticleSummaryResponse> getPendingArticles(int pageNum, int pageSize);
}
