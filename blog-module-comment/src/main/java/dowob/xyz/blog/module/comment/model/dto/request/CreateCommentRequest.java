package dowob.xyz.blog.module.comment.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * 建立留言請求 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class CreateCommentRequest {

    /**
     * 留言內容（Markdown 格式，最多 2000 字）
     */
    @NotBlank
    @Size(max = 2000)
    private String content;

    /**
     * 父留言 UUID；null 表示頂層留言
     */
    private UUID parentUuid;
}
