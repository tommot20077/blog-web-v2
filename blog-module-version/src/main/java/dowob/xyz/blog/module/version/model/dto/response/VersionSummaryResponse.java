package dowob.xyz.blog.module.version.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 版本列表用 summary（不含 content，給列表頁輕量用）。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VersionSummaryResponse {
    private UUID uuid;
    private String type;
    private String note;
    private LocalDateTime createdAt;
    private Long authorId;
    private int contentLength;
}
