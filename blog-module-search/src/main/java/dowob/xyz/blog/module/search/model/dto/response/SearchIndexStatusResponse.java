package dowob.xyz.blog.module.search.model.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 搜尋索引狀態回應 DTO
 *
 * <p>
 * 供 Admin 後台查詢 Elasticsearch 索引的健康狀態、文件數與最後一次全量重建時間，
 * 使儀表板能顯示索引運維概況。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Builder
public class SearchIndexStatusResponse {

    /**
     * Elasticsearch 索引內的文件數；查詢 ES 失敗時為 {@code null}
     */
    private Long documentCount;

    /**
     * 最後一次全量重建索引的時間（ISO-8601 字串）；從未重建過時為 {@code null}
     */
    private String lastReindexAt;

    /**
     * Elasticsearch 是否可達；查詢成功為 {@code true}，查詢拋出例外時為 {@code false}
     */
    private boolean healthy;
}
