package dowob.xyz.blog.infrastructure.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 系列刪除事件（跨模組共用契約）。
 *
 * <p>series 模組在<b>刪除交易 commit 後</b>發送（best-effort，見 {@code SeriesEventPublisher}），
 * 通知其他模組「此系列已不存在」。目前<b>沒有 consumer</b>：article 模組於 M6（{@code articles.series_id}
 * 改為 {@code series_uuid} 時）訂閱此事件、把文章的 series 歸屬清空；在那之前，文章脫離系列仍由資料庫的
 * {@code articles.series_id → series(id) ON DELETE SET NULL} 負責，行為不變。</p>
 *
 * <p>事件只帶 UUID（設計：跨模組識別一律 UUID）。刪除後 series 列已不存在，consumer 只需要 series 的公開
 * UUID 即可（以 {@code series_uuid = ?} 清空歸屬天然冪等）；{@code eventId} 供 consumer 冪等去重。</p>
 *
 * @param eventId    事件 dedup key（producer 每次 publish 時 random gen）
 * @param seriesUuid 被刪除的系列公開 UUID
 * @param occurredAt 事件發生時間
 *
 * @author Yuan
 * @version 1.0
 */
public record SeriesDeletedEvent(
    UUID eventId,
    UUID seriesUuid,
    Instant occurredAt
) {}
