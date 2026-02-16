package dowob.xyz.blog.module.search.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
@RestController
@RequestMapping("/api/admin/search")
@RequiredArgsConstructor
public class AdminSearchController {

    /**
     * 搜尋服務
     */
    private final SearchService searchService;

    /**
     * 全量重建 Elasticsearch 索引
     *
     * <p>
     * 從資料庫讀取所有已發布文章，批次同步至 Elasticsearch。
     * 此操作適用於首次部署或索引資料異常時的修復。
     * </p>
     *
     * @return 成功回應
     */
    @PostMapping("/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> reindex() {
        searchService.reindexAll();
        return ApiResponse.success(null, "Elasticsearch 索引全量重建已完成");
    }
}
