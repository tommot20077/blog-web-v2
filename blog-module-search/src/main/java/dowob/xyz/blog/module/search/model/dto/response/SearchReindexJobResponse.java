package dowob.xyz.blog.module.search.model.dto.response;

import dowob.xyz.blog.module.search.reindex.ReindexJobState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 搜尋索引全量重建作業的狀態回應 DTO（D1／PERF-01）
 *
 * <p>
 * {@code POST /api/v1/admin/search/reindex} 的回應（202 時為剛建立的作業，409 時為進行中的作業），
 * 以及 {@code GET /api/v1/admin/search/status} 的 {@code reindex} 欄位（最近一次作業）。
 * 狀態存在 Redis，多個實例看到的是同一份。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchReindexJobResponse {

    /**
     * 作業識別碼
     */
    private UUID jobId;

    /**
     * 作業狀態：RUNNING／COMPLETED／FAILED
     */
    private ReindexJobState state;

    /**
     * 已寫入新索引的文章數
     */
    private long processed;

    /**
     * 開始時的已發布文章總數（進度分母）；背景作業尚未算出時為 {@code null}
     */
    private Long total;

    /**
     * 開始時間
     */
    private LocalDateTime startedAt;

    /**
     * 結束時間；執行中或執行個體中斷時為 {@code null}
     */
    private LocalDateTime finishedAt;

    /**
     * 失敗時的錯誤摘要；其餘狀態為 {@code null}
     */
    private String error;
}
