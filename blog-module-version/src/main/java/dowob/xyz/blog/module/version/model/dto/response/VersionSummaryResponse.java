package dowob.xyz.blog.module.version.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 版本列表用 summary（不含 content，給列表頁輕量用）。
 *
 * <p>
 * 依 {@code architecture.md}「All external IDs must be UUIDs」，本 DTO 不含任何內部 Long ID。
 * 原有的 {@code authorId} 已移除，理由同 {@link VersionDetailResponse}：其值為文章作者而非
 * 快照建立者，對呼叫端無資訊價值。移除後 {@code VersionMapper.listSummaries} 的 SQL
 * 不再投影 {@code author_id}，故列表也不需為此 JOIN users。
 * </p>
 *
 * @author Yuan
 * @version 2.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VersionSummaryResponse {
    private UUID uuid;
    private String type;
    private String note;
    private LocalDateTime createdAt;
    private int contentLength;
}
