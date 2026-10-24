package dowob.xyz.blog.infrastructure.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文章發布事件（跨模組共用定義）
 *
 * <p>
 * 文章成功發布後，透過 RabbitMQ 廣播此事件，
 * 供推薦模組清快取、搜尋模組建立索引等下游消費。
 * 此事件定義放置於 blog-infrastructure 以供各模組引用，
 * 避免模組間直接相依。
 * </p>
 *
 * <p><b>過渡欄位（只增不減）</b>：{@code authorId} 保留，現有 consumer 仍讀取；
 * 發送端同時填入 {@code authorUuid}。Long 欄位將於 M6（article 階段）移除。</p>
 *
 * <p>Backward compatibility：升級前的 in-flight 舊 message 沒有 {@code eventId}／{@code authorUuid}，
 * Jackson 反序列化為 null，consumer 須自行防禦。</p>
 *
 * @param eventId        事件 dedup key（producer 每次 publish 時 random gen；舊訊息為 null）
 * @param articleUuid    文章公開 UUID
 * @param authorId       作者資料庫主鍵（過渡欄位，將於 M6 移除；search／recommend 目前仍讀取）
 * @param authorUuid     作者公開 UUID（nullable — 舊 message 或 uuid 查詢失敗時為 null）
 * @param title          文章標題
 * @param publishedAt    發布時間
 * @param slug           文章 URL slug
 * @param summary        文章摘要
 * @param contentText    Markdown 去格式後的純文字（供 Elasticsearch 全文索引）
 * @param authorUsername 作者帳號名稱
 * @param authorNickname 作者暱稱
 * @param tags           文章標籤列表
 *
 * @author Yuan
 * @version 2.0（新增 eventId、authorUuid）
 */
public record ArticlePublishedEvent(
        UUID eventId,
        UUID articleUuid,
        Long authorId,
        UUID authorUuid,
        String title,
        LocalDateTime publishedAt,
        String slug,
        String summary,
        String contentText,
        String authorUsername,
        String authorNickname,
        List<TagInfo> tags) {
}
