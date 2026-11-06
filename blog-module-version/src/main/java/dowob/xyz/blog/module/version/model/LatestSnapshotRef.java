package dowob.xyz.blog.module.version.model;

import java.time.LocalDateTime;

/**
 * 某文章最新一筆指定類型快照的輕量參照（PERF-21）：只含主鍵與建立時間，不含內容、也不含長度。
 *
 * <p>自動快照的判斷分兩步：先用本投影檢查時間間隔（連續存檔時絕大多數事件在這一步就結束），
 * 通過才以 {@code VersionMapper#findContentLength} 對這一列的主鍵計算內容長度，
 * 避免每個事件都對整份 Markdown 做全文掃描。SELECT 欄位順序（id, created_at）必須與本 record 元件順序一致。</p>
 *
 * @param id        快照主鍵
 * @param createdAt 快照建立時間
 * @author Yuan
 * @version 1.0
 */
public record LatestSnapshotRef(Long id, LocalDateTime createdAt) {
}
