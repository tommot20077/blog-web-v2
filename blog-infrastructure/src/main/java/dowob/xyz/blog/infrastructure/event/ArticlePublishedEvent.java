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
 * <p><b>M6-a：Long 過渡欄位已移除</b>。原本保留的 {@code authorId}（Long）與登入識別符 {@code authorUsername}
 * 已從 record 移除——search、recommend 皆已改讀 {@code authorUuid}／{@code authorNickname}。
 * 升級當下佇列中仍含這兩個舊欄位的 in-flight 訊息可正常反序列化（多出的欄位被忽略）。</p>
 *
 * <p>Backward compatibility：升級前的 in-flight 舊 message 沒有 {@code eventId}／{@code authorUuid}，
 * Jackson 反序列化為 null，consumer 須自行防禦。</p>
 *
 * @param eventId        事件 dedup key（producer 每次 publish 時 random gen；舊訊息為 null）
 * @param articleUuid    文章公開 UUID
 * @param authorUuid     作者公開 UUID（nullable — 舊 message 或 uuid 查詢失敗時為 null）
 * @param title          文章標題
 * @param publishedAt    發布時間
 * @param slug           文章 URL slug
 * @param summary        文章摘要
 * @param contentText    Markdown 去格式後的純文字（供 Elasticsearch 全文索引）
 * @param authorNickname 作者暱稱
 * @param tags           文章標籤列表
 *
 * @author Yuan
 * @version 3.0（M6-a：移除 authorId、authorUsername；2.0 新增 eventId、authorUuid）
 */
public record ArticlePublishedEvent(
        UUID eventId,
        UUID articleUuid,
        UUID authorUuid,
        String title,
        LocalDateTime publishedAt,
        String slug,
        String summary,
        String contentText,
        String authorNickname,
        List<TagInfo> tags) {
}
