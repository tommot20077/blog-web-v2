package dowob.xyz.blog.infrastructure.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 使用者帳號刪除事件（跨模組共用契約）。
 *
 * <p>user 模組在<b>匿名化交易 commit 後</b>發送（best-effort，見 {@code UserEventPublisher}），
 * 通知其他模組「此使用者已刪除帳號」（DATA-02，Q2 定案：匿名化）。各模組依本事件清理該使用者的個人資料：
 * article 刪除其草稿／待審／被駁回文章（已發布文章保留）、reading 刪除收藏／劃線／閱讀進度（按讚保留）、
 * version 刪除偏好設定、tag 刪除標籤追蹤、file 刪除未被保留文章引用的上傳檔案、
 * search 把已發布文章在索引內冗餘的作者暱稱改為匿名暱稱並清除搜尋歷史。</p>
 *
 * <p>事件只帶 UUID（設計：跨模組識別一律 UUID），<b>刻意不帶 email、暱稱等個資</b>：
 * 匿名化後這些欄位已不是原值，需要顯示資訊的訂閱端一律走 {@code UserFacade}。
 * 各 consumer 的清理皆以 {@code user_uuid = ?} 為條件，天然冪等；{@code eventId} 供需要去重的 consumer 使用。</p>
 *
 * @param eventId    事件 dedup key（producer 每次 publish 時 random gen）
 * @param userUuid   被刪除（已匿名化）的使用者公開 UUID
 * @param occurredAt 事件發生時間
 *
 * @author Yuan
 * @version 1.0
 */
public record UserDeletedEvent(
    UUID eventId,
    UUID userUuid,
    Instant occurredAt
) {}
