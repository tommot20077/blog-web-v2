package dowob.xyz.blog.module.version.model.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 建立手動快照請求。note 為 optional 命名。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class CreateManualSnapshotRequest {
    @Size(max = 255)
    private String note;
}
