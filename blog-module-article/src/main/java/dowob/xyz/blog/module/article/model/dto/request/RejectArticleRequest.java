package dowob.xyz.blog.module.article.model.dto.request;

import jakarta.validation.constraints.Size;
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
     * 駁回原因說明（可為 null）；{@code articles.reject_reason} 為 TEXT 無資料庫上限，
     * 上限 500 字與文章摘要一致（SEC-24）
     */
    @Size(max = 500, message = "駁回原因長度不得超過 500 字")
    private String reason;
}
