package dowob.xyz.blog.module.version.model.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 版本詳情（含完整快照內容）— 給預覽 / restore 確認用。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class VersionDetailResponse {
    private UUID uuid;
    private String type;
    private String note;
    private LocalDateTime createdAt;
    private Long authorId;

    private String title;
    private String slug;
    private String content;
    private String summary;
    private Long categoryId;
    private String coverImageUrl;
    private String status;
    private List<UUID> tags;
}
