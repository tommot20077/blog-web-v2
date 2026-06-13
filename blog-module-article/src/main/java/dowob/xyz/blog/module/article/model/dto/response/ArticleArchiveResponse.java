package dowob.xyz.blog.module.article.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文章歸檔回應 DTO（年度歸檔精簡投影）
 *
 * <p>
 * 供前端「年度歸檔」頁面使用的精簡投影，僅含建立時間軸與導覽所需的最小欄位，
 * 不含內容、計數、作者等資訊以降低資料傳輸量。年度分組由前端依 publishedAt 自行處理。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Builder
public class ArticleArchiveResponse {

    /**
     * 文章公開 UUID
     */
    private UUID uuid;

    /**
     * 文章標題
     */
    private String title;

    /**
     * URL slug（SEO 友善網址）
     */
    private String slug;

    /**
     * 發布時間（年度歸檔分組與排序依據）
     */
    private LocalDateTime publishedAt;

    /**
     * 文章標籤名稱清單
     */
    private List<String> tags;
}
