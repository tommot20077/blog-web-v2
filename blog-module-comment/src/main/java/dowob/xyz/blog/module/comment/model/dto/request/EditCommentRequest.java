package dowob.xyz.blog.module.comment.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 編輯留言請求 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class EditCommentRequest {

    /**
     * 更新後的留言內容（Markdown 格式，最多 2000 字）
     */
    @NotBlank
    @Size(max = 2000)
    private String content;
}
