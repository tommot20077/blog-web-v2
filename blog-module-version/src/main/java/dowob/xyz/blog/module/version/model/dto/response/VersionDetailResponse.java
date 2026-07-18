package dowob.xyz.blog.module.version.model.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 版本詳情（含完整快照內容）— 給預覽 / restore 確認用。
 *
 * <p>
 * 依 {@code architecture.md}「All external IDs must be UUIDs」，本 DTO 不含任何內部 Long ID。
 * 原有的 {@code authorId} 已移除：其值取自 <b>文章作者</b>而非快照建立者
 * （見 {@code VersioningService.snapshotFromContent}），對同一篇文章的每個版本恆為同值，
 * 而呼叫端必然已持有該文章、早已知道作者是誰，故對外無資訊價值。
 * 版本的存取控制在 service 層以 entity 的 authorId 進行，不倚賴本 DTO。
 * 原有的 {@code categoryId} 亦已移除：article 早已改為多對多分類（{@code article_categories}），
 * {@code Article} entity 無單一 category 欄位，快照流程從未寫入該值 —— 該欄位恆為 null。
 * </p>
 *
 * @author Yuan
 * @version 2.0
 */
@Data
public class VersionDetailResponse {
    private UUID uuid;
    private String type;
    private String note;
    private LocalDateTime createdAt;

    private String title;
    private String slug;
    private String content;
    private String summary;
    private String coverImageUrl;
    private String status;
    private List<UUID> tags;
}
