package dowob.xyz.blog.module.version.model;

import java.time.LocalDateTime;

/**
 * 某文章最新一筆指定類型快照的輕量投影（PERF-21）：只含建立時間與「內容長度」，不含內容本身。
 *
 * <p>自動快照的觸發判斷只需要「距上次多久」與「內容長度差多少」，把整份 Markdown 撈回應用程式只為了
 * {@code String.length()} 是浪費。{@code contentLength} 由資料庫算出，單位與 Java {@code String.length()}
 * 一致（UTF-16 code unit，補充平面字元如 emoji 算 2），見 {@code VersionMapper#findLatestMeta}。
 * SELECT 欄位順序（created_at, content_length）必須與本 record 元件順序一致，不可調整。</p>
 *
 * @param createdAt     快照建立時間
 * @param contentLength 快照內容長度（UTF-16 code unit 數，與 Java {@code String.length()} 一致）
 * @author Yuan
 * @version 1.0
 */
public record LatestSnapshotMeta(LocalDateTime createdAt, int contentLength) {
}
