package dowob.xyz.blog.module.comment.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 作者摘要 DTO，用於留言回應中的作者資訊表達
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthorSummary {

    /**
     * 作者 UUID
     */
    private UUID uuid;

    /**
     * 作者暱稱
     */
    private String nickname;

    /**
     * 作者頭像 URL
     */
    private String avatarUrl;
}
