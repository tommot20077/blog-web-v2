package dowob.xyz.blog.infrastructure.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 系列刪除事件（跨模組共用契約）。
 *
 * <p>series 模組在<b>刪除交易 commit 後</b>發送（best-effort，見 {@code SeriesEventPublisher}），
 * 通知其他模組「此系列已不存在」。article 模組（M6-b 起）訂閱此事件，把成員文章的
 * {@code articles.series_uuid} 與 {@code series_position} 清空（{@code ArticleSeriesDeletedConsumer}）；
 * 因為 {@code articles.series_uuid} 沒有資料庫 FK，原本由 {@code ON DELETE SET NULL} 完成的連動改由
 * 本事件負責，事件遺失時由 article 端的每日校正排程（{@code ArticleSeriesOrphanReconciliationJob}）兜底。
 * 刪除語意不變：文章留存、歸屬清空。</p>
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
