package dowob.xyz.blog.module.search.controller;

import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.SearchErrorCode;
import dowob.xyz.blog.common.api.request.PageQuery;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.module.search.model.dto.response.SearchResultResponse;
import dowob.xyz.blog.module.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 搜尋 Controller
 *
 * <p>
 * 提供全文搜尋、熱門搜尋建議、個人搜尋歷史等 API。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Tag(name = "搜尋")
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    /** 搜尋字串 {@code q} 的長度上限（含 suggest）；超過回 400 {@code PARAM_VALID_ERROR}（SEC-05） */
    public static final int MAX_QUERY_LENGTH = 100;

    /**
     * 搜尋服務
     */
    private final SearchService searchService;

    /**
     * 檢查搜尋字串長度。
     *
     * @param q 搜尋字串（可為 null）
     * @throws BusinessException 長度超過 {@link #MAX_QUERY_LENGTH}（{@link CommonErrorCode#PARAM_VALID_ERROR}）
     */
    private static void requireQueryLength(String q) {
        if (q != null && q.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR, "q 長度不可超過 " + MAX_QUERY_LENGTH);
        }
    }

    /**
     * 全文搜尋文章
     *
     * <p>
     * 支援關鍵字、標籤過濾與排序選項。
     * 同時記錄搜尋歷史（需登入）與熱門搜尋統計。
     * </p>
     *
     * <p>結果中的 {@code viewCount}／{@code likeCount} 與文章詳情頁<b>同源</b>：
     * 瀏覽數 = DB 值 + Redis 暫存增量，按讚數 = DB 值（B4）。{@code sort} 僅接受 {@code relevance}／{@code latest}（不分大小寫），其他值（含已移除的 {@code hot}）回 400。</p>
     *
     * @param q    搜尋關鍵字（長度上限 {@value #MAX_QUERY_LENGTH}，超過回 400）
     * @param tag  標籤 slug 過濾（可選）
     * @param sort 排序：{@code relevance}（預設）/ {@code latest}（不分大小寫）；其他值（含已移除的 {@code hot}）回 400 {@code PARAM_VALID_ERROR}
     * @param pageQuery  分頁參數（query string 仍為 {@code page} / {@code size}；
     *                   {@code size} 上限見 {@link PageQuery#MAX_SIZE}）
     * @param user 當前登入用戶（匿名為 null，用於記錄搜尋歷史）
     * @return 分頁搜尋結果
     */
    @GetMapping
    @RateLimited("search")
    @Operation(summary = "全文搜尋文章（匿名可存取）",
            description = "viewCount／likeCount 與文章詳情頁同源（瀏覽數 = DB 值 + Redis 暫存增量；按讚數 = DB 值）。"
                    + "sort 僅支援 relevance（預設）與 latest（不分大小寫），其他值（含已移除的 hot）回 400 A0001。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗（q 長度超過 100、page × size 超過搜尋深度上限、sort 不是 relevance／latest）")
    public ApiResponse<PageResult<SearchResultResponse>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @Parameter(description = "排序：relevance（預設）/ latest（不分大小寫），其他值回 400",
                    schema = @Schema(allowableValues = {"relevance", "latest"}))
            @RequestParam(defaultValue = "relevance") String sort,
            PageQuery pageQuery,
            @AuthenticationPrincipal AuthUser user) {
        requireQueryLength(q);
        UUID userUuid = user == null ? null : user.uuid();
        return ApiResponse.success(searchService.search(q, tag, sort, pageQuery.page(), pageQuery.sizeOrDefault(10), userUuid));
    }

    /**
     * 熱門搜尋詞前綴建議
     *
     * <p>
     * 根據輸入前綴從 Redis 熱門搜尋詞中回傳建議，最多 10 筆。
     * </p>
     *
     * @param q 搜尋前綴
     * @return 搜尋詞建議列表
     */
    @Operation(summary = "搜尋建議",
            description = "公開（匿名可存取）。依輸入前綴回傳搜尋建議；q 長度上限 100。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗（q 長度超過 100）")
    @GetMapping("/suggest")
    @RateLimited("search-suggest")
    public ApiResponse<List<String>> suggest(
            @RequestParam(required = false, defaultValue = "") String q) {
        requireQueryLength(q);
        return ApiResponse.success(searchService.suggest(q));
    }

    /**
     * 取得個人搜尋歷史
     *
     * <p>
     * 需登入，回傳最近 20 筆搜尋紀錄。
     * </p>
     *
     * @param user 當前登入用戶
     * @return 搜尋歷史列表（最新在前）
     */
    @Operation(summary = "取得個人搜尋歷史",
            description = "登入。回傳最近 20 筆搜尋紀錄（最新在前）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0502 無法識別當前登入用戶（principal 無法解析，防禦性檢查）")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/history")
    public ApiResponse<List<String>> getHistory(@AuthenticationPrincipal AuthUser user) {
        if (user == null) {
            throw new BusinessException(SearchErrorCode.PRINCIPAL_UNRESOLVED);
        }
        return ApiResponse.success(searchService.getHistory(user.uuid()));
    }

    /**
     * 清除個人搜尋歷史
     *
     * <p>
     * 需登入，刪除所有個人搜尋紀錄。
     * </p>
     *
     * @param user 當前登入用戶
     * @return 成功回應
     */
    @Operation(summary = "清除個人搜尋歷史",
            description = "登入。刪除所有個人搜尋紀錄。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0502 無法識別當前登入用戶（principal 無法解析，防禦性檢查）")
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/history")
    public ApiResponse<Void> clearHistory(@AuthenticationPrincipal AuthUser user) {
        if (user == null) {
            throw new BusinessException(SearchErrorCode.PRINCIPAL_UNRESOLVED);
        }
        searchService.clearHistory(user.uuid());
        return ApiResponse.success();
    }
}
