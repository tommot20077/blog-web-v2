package dowob.xyz.blog.module.article.model.dto.request;

import lombok.Data;

/**
 * 駁回文章請求 DTO
 *
 * <p>
 * 管理員駁回待審文章時，附帶說明原因。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class RejectArticleRequest {

    /**
     * 駁回原因說明
     */
    private String reason;
}
