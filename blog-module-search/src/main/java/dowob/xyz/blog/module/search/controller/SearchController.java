package dowob.xyz.blog.module.search.controller;

import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.search.model.dto.response.SearchResultResponse;
import dowob.xyz.blog.module.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    /**
     * 搜尋服務
     */
    private final SearchService searchService;

    /**
     * 全文搜尋文章
     *
     * <p>
     * 支援關鍵字、標籤過濾與排序選項。
     * 同時記錄搜尋歷史（需登入）與熱門搜尋統計。
     * </p>
     *
     * @param q    搜尋關鍵字
     * @param tag  標籤 slug 過濾（可選）
     * @param sort 排序：{@code relevance}（預設）/ {@code latest} / {@code hot}
     * @param page   頁碼（從 1 開始，預設 1）
     * @param size   每頁筆數（預設 10）
     * @param userId 當前登入用戶的資料庫主鍵（匿名為 null，用於記錄搜尋歷史）
     * @return 分頁搜尋結果
     */
    @GetMapping
    public ApiResponse<PageResult<SearchResultResponse>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal Long userId) {
        return ApiResponse.success(searchService.search(q, tag, sort, page, size, userId));
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
    @GetMapping("/suggest")
    public ApiResponse<List<String>> suggest(
            @RequestParam(required = false, defaultValue = "") String q) {
        return ApiResponse.success(searchService.suggest(q));
    }

    /**
     * 取得個人搜尋歷史
     *
     * <p>
     * 需登入，回傳最近 20 筆搜尋紀錄。
     * </p>
     *
     * @param userId 當前登入用戶的資料庫主鍵
     * @return 搜尋歷史列表（最新在前）
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/history")
    public ApiResponse<List<String>> getHistory(@AuthenticationPrincipal Long userId) {
        if (userId == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }
        return ApiResponse.success(searchService.getHistory(userId));
    }

    /**
     * 清除個人搜尋歷史
     *
     * <p>
     * 需登入，刪除所有個人搜尋紀錄。
     * </p>
     *
     * @param userId 當前登入用戶的資料庫主鍵
     * @return 成功回應
     */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/history")
    public ApiResponse<Void> clearHistory(@AuthenticationPrincipal Long userId) {
        if (userId == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }
        searchService.clearHistory(userId);
        return ApiResponse.success();
    }
}
