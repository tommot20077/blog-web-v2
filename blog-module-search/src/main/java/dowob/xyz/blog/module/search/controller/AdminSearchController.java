package dowob.xyz.blog.module.search.controller;

import dowob.xyz.blog.common.api.errorcode.SearchErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.search.model.dto.response.SearchIndexStatusResponse;
import dowob.xyz.blog.module.search.model.dto.response.SearchReindexJobResponse;
import dowob.xyz.blog.module.search.reindex.ReindexStartResult;
import dowob.xyz.blog.module.search.reindex.SearchReindexService;
import dowob.xyz.blog.module.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 搜尋模組管理員 Controller
 *
 * <p>
 * 提供 Elasticsearch 索引管理功能，僅限 ADMIN 使用。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Tag(name = "搜尋管理")
@RestController
@RequestMapping("/api/v1/admin/search")
@RequiredArgsConstructor
public class AdminSearchController {

    /**
     * 搜尋服務
     */
    private final SearchService searchService;

    /**
     * 背景全量重建服務
     */
    private final SearchReindexService reindexService;

    /**
     * 觸發 Elasticsearch 索引全量重建（背景執行）
     *
     * <p>
     * 立即回 <b>202 Accepted</b>，{@code data} 為剛建立的作業（含 {@code jobId}、{@code state=RUNNING}）；
     * 進度與結果以 {@code GET /api/v1/admin/search/status} 的 {@code reindex} 欄位輪詢。
     * 重建會以 annotation mapping 建立新的實體索引、分批寫入已發布文章、補齊重建期間的變動，
     * 完成後原子切換 alias 並刪除舊索引；失敗時 alias 維持指向舊索引。
     * 同一時間只能有一個重建：已有作業在跑時回 <b>409</b>（{@code A0501}），{@code data} 為進行中的作業。
     * 適用於首次部署、索引資料異常或 mapping 變更時的修復。
     * </p>
     *
     * @return 202 與新作業；或 409 與進行中的作業
     */
    @Operation(summary = "觸發搜尋索引全量重建",
            description = "需 ADMIN_SEARCH 權限。於背景重建 Elasticsearch 索引並在完成後原子切換 alias；立即回 202 與作業資訊，進度以 /status 查詢。同一時間只允許一個重建作業。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "已開始於背景重建，body 為剛建立的作業")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "A0501 已有搜尋索引重建作業執行中，body 為進行中的作業")
    @PostMapping("/reindex")
    @PreAuthorize("hasAuthority('ADMIN_SEARCH')")
    public ResponseEntity<ApiResponse<SearchReindexJobResponse>> reindex() {
        ReindexStartResult result = reindexService.start();
        if (result.started()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(result.job(), "已開始於背景重建 Elasticsearch 索引"));
        }
        ApiResponse<SearchReindexJobResponse> body = ApiResponse.failed(SearchErrorCode.REINDEX_IN_PROGRESS);
        body.setData(result.job());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 查詢搜尋索引狀態
     *
     * <p>
     * 供 Admin 後台儀表板顯示 Elasticsearch 索引運維概況：文件數、
     * 最後一次全量重建時間、ES 是否可達，以及最近一次全量重建作業（{@code reindex}：
     * 狀態 RUNNING／COMPLETED／FAILED、已處理篇數／總數、開始與結束時間、錯誤摘要）。
     * </p>
     *
     * @return 搜尋索引狀態
     */
    @Operation(summary = "查詢搜尋索引狀態",
            description = "需 ADMIN_SEARCH 權限。回傳索引健康狀態與最近一次重建作業。")
    @GetMapping("/status")
    @PreAuthorize("hasAuthority('ADMIN_SEARCH')")
    public ApiResponse<SearchIndexStatusResponse> status() {
        return ApiResponse.success(searchService.getIndexStatus());
    }
}
