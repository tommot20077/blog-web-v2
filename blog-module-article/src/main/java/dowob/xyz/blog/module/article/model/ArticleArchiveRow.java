package dowob.xyz.blog.module.article.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 歸檔頁查詢的列投影（只含歸檔回應需要的欄位）
 *
 * <p>刻意<b>不含</b> {@code content_md}／{@code content_html}／{@code toc} 等大文字欄位：
 * 歸檔頁一次列出全站已發布文章，若沿用 {@code SELECT *} 就會把整個語料搬進 JVM 堆（PERF-04）。
 * 標籤另以批次查詢補上，不在此列。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class ArticleArchiveRow {

    /** 文章公開 UUID */
    private UUID uuid;

    /** 文章標題 */
    private String title;

    /** URL slug */
    private String slug;

    /** 發布時間 */
    private LocalDateTime publishedAt;
}
