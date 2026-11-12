package dowob.xyz.blog.module.comment.controller;

import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import dowob.xyz.blog.common.api.request.PageQuery;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.request.EditCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.response.ArticleCommentListResponse;
import dowob.xyz.blog.module.comment.model.dto.response.CommentResponse;
import dowob.xyz.blog.module.comment.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 留言 Controller。
 *
 * <p>5 個端點：
 * <ul>
 *   <li>GET /articles/{uuid}/comments — 公開列表（含軟刪除佔位；每則頂層只內嵌前 3 則回覆）</li>
 *   <li>GET /comments/{uuid}/replies — 公開：頂層留言的回覆分頁（載入更多）</li>
 *   <li>POST /articles/{uuid}/comments — 登入：建立 top-level 或 reply</li>
 *   <li>PUT /comments/{uuid} — 登入 + 作者 / Admin：編輯（5 分鐘窗）</li>
 *   <li>DELETE /comments/{uuid} — 登入 + 作者 / Admin：軟刪除</li>
 * </ul>
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "留言")
public class CommentController {

    private final CommentService commentService;

    /**
     * 列出文章留言。
     *
     * <p>
     * <b>匿名可存取</b>——本端點依 {@code security.md} 原則 7 的「選填認證公開端點」豁免，
     * 故不標註 {@code @PreAuthorize}。三項豁免條件均成立：
     * </p>
     * <ol>
     *   <li>路徑 {@code GET /api/v1/articles/{uuid}/comments} 於 {@code SecurityConfig} 以明確清單 {@code permitAll}
     *       （UUID 形狀比對，非萬用字元；見 {@code SecurityConfig.PUBLIC_ARTICLE_COMMENTS}）；</li>
     *   <li>{@code currentUserUuid} 允許為 null，僅用於選填個人化（如「已按讚」標記）；</li>
     *   <li>本 JavaDoc 即為所需的豁免標註。</li>
     * </ol>
     *
     * <p>「匿名可到達」不等於「內容公開」：文章本身若非公開（含 <b>ARCHIVED 下架</b>），
     * {@link CommentService#listComments} 會回 200 + 空清單，僅作者本人與 ADMIN 讀得到。
     * {@code currentUserUuid} 與 {@code isAdmin} 即為該判斷的輸入。</p>
     *
     * @param articleUuid   文章公開 UUID
     * @param pageQuery  分頁參數（query string 仍為 {@code page} / {@code size}；
     *                   {@code size} 上限見 {@link PageQuery#MAX_SIZE}）
     * @param sort          排序方式（newest / oldest 等）
     * @param user          當前登入者（未登入為 null，僅供個人化）
     * @return 留言列表（含軟刪除佔位）
     */
    @GetMapping("/articles/{articleUuid}/comments")
    @Operation(summary = "列出文章留言",
            description = "公開（匿名可存取）。分頁列出頂層留言，每則內嵌前 3 則回覆；軟刪除的留言以佔位呈現。文章不存在或對檢視者不可讀時回 200 空頁，不形成存在性探測。sort 支援 newest（預設）與 oldest。")
    public ApiResponse<ArticleCommentListResponse> list(
            @PathVariable UUID articleUuid,
            PageQuery pageQuery,
            @RequestParam(defaultValue = "newest") String sort,
            @AuthenticationPrincipal AuthUser user) {
        boolean isAdmin = SecurityUtils.isAdmin();
        return ApiResponse.success(
                commentService.listComments(articleUuid, (user == null ? null : user.uuid()), isAdmin, sort, pageQuery.page(), pageQuery.sizeOrDefault(20)));
    }

    /**
     * 列出頂層留言的回覆（分頁，「載入更多」，D3）。
     *
     * <p>
     * <b>匿名可存取</b>——與 {@link #list} 同為 {@code security.md} 原則 7 的「選填認證公開端點」豁免，
     * 三項豁免條件均成立：
     * </p>
     * <ol>
     *   <li>路徑 {@code GET /api/v1/comments/{commentUuid}/replies} 於 {@code SecurityConfig} 以明確清單 {@code permitAll}
     *       （UUID 形狀比對，非萬用字元；見 {@code SecurityConfig.PUBLIC_COMMENT_REPLIES}），且只開放 GET；</li>
     *   <li>{@code currentUserUuid} 允許為 null，僅用於選填個人化（「已按讚」標記）與文章可讀性判斷；</li>
     *   <li>本 JavaDoc 即為所需的豁免標註。</li>
     * </ol>
     *
     * <p>「匿名可到達」不等於「內容公開」：文章對檢視者不可讀（草稿、下架、不存在）、留言不存在、
     * 傳入的是回覆（非頂層）的 uuid，三者一律回 <b>200 ＋空頁</b>（total 為 0），回應完全相同，
     * 與 {@link #list} 一致，避免被用來探測 uuid 是否存在。
     * 列表 API 每則頂層只內嵌前 3 則回覆，其餘由本端點以相同排序（created_at 升冪）分頁取得。</p>
     *
     * @param commentUuid 頂層留言公開 UUID
     * @param pageQuery   分頁參數（query string 仍為 {@code page} / {@code size}，page 自 1 起；
     *                    {@code size} 上限見 {@link PageQuery#MAX_SIZE}，超出回 400；預設 10）
     * @param user        當前登入者（未登入為 null，僅供個人化與可讀性判斷）
     * @return 回覆分頁（{@code total} 為未刪除回覆總數）
     */
    @GetMapping("/comments/{commentUuid}/replies")
    @Operation(summary = "列出頂層留言的回覆",
            description = "公開（匿名可存取）。排序為 created_at 升冪，size 上限 100。留言不存在、傳入回覆（非頂層）的 uuid、文章對檢視者不可讀，三者一律回 200 且 total 為 0 的空頁，與留言列表一致，不形成存在性探測。")
    public ApiResponse<PageResult<CommentResponse>> listReplies(
            @PathVariable UUID commentUuid,
            PageQuery pageQuery,
            @AuthenticationPrincipal AuthUser user) {
        boolean isAdmin = SecurityUtils.isAdmin();
        return ApiResponse.success(commentService.listReplies(
                commentUuid, (user == null ? null : user.uuid()), isAdmin, pageQuery.page(), pageQuery.sizeOrDefault(10)));
    }

    @PostMapping("/articles/{articleUuid}/comments")
    @RateLimited("comment-write")
    @PreAuthorize("hasAuthority('COMMENT_WRITE')")
    @Operation(summary = "建立留言",
            description = "需 COMMENT_WRITE 權限。可建立頂層留言或回覆（最多 2 層巢狀）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0201 文章不存在或對呼叫者不可讀；C0101 父留言不存在；C0105 父留言不屬於此文章；C0102 巢狀超過 2 層；C0106 父留言已凍結")
    public ApiResponse<CommentResponse> create(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody CreateCommentRequest req) {
        return ApiResponse.success(commentService.createComment(articleUuid, user.uuid(), SecurityUtils.isAdmin(), req));
    }

    @PutMapping("/comments/{uuid}")
    @RateLimited("comment-write")
    @PreAuthorize("hasAuthority('COMMENT_WRITE')")
    @Operation(summary = "編輯留言",
            description = "需 COMMENT_WRITE 權限；作者本人於建立後 5 分鐘內可編輯，管理員不受時限限制。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；C0101 留言不存在；A0201 文章不存在或對呼叫者不可讀；C0104 留言已刪除，無法操作；C0103 編輯時限已過")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "A0006 非留言作者（管理員不受限）")
    public ApiResponse<CommentResponse> edit(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody EditCommentRequest req) {
        boolean isAdmin = SecurityUtils.isAdmin();
        return ApiResponse.success(commentService.editComment(uuid, user.uuid(), isAdmin, req));
    }

    @DeleteMapping("/comments/{uuid}")
    @PreAuthorize("hasAuthority('COMMENT_DELETE')")
    @Operation(summary = "刪除留言",
            description = "需 COMMENT_DELETE 權限；作者本人或管理員。軟刪除，已刪除的留言再刪為冪等。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "C0101 留言不存在")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "A0006 非留言作者（管理員不受限）")
    public ApiResponse<Void> delete(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal AuthUser user) {
        boolean isAdmin = SecurityUtils.isAdmin();
        commentService.deleteComment(uuid, user.uuid(), isAdmin);
        return ApiResponse.success();
    }
}
