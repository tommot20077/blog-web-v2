package dowob.xyz.blog.module.article.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * 標籤摘要回應 DTO
 *
 * <p>
 * 用於文章回應中嵌入的輕量標籤資訊，不含統計欄位。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Builder
public class TagSummaryResponse {

    /**
     * 標籤公開 UUID
     */
    private UUID id;

    /**
     * 標籤名稱
     */
    private String name;

    /**
     * 標籤 URL slug
     */
    private String slug;
}
