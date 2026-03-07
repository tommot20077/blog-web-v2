package dowob.xyz.blog.module.search.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文章 Elasticsearch Document
 *
 * <p>
 * 對應 Elasticsearch 索引 {@code blog_articles}，
 * 包含全文檢索所需欄位。
 * 分詞器由 Elasticsearch 索引 Settings 配置（IK Analyzer），
 * 此 Java 映射採預設，部署時請透過索引範本（Index Template）套用 IK。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "blog_articles", createIndex = false)
public class ArticleDocument {

    /**
     * Elasticsearch 文件 ID（使用文章 UUID 字串）
     */
    @Id
    private String id;

    /**
     * 文章標題（全文檢索，boost 3.0）
     */
    @Field(type = FieldType.Text)
    private String title;

    /**
     * 文章摘要（全文檢索，boost 2.0）
     */
    @Field(type = FieldType.Text)
    private String summary;

    /**
     * 文章純文字內容，Markdown 去格式後（全文檢索，boost 1.0）
     */
    @Field(type = FieldType.Text)
    private String content;

    /**
     * 文章 URL slug（keyword，用於回傳連結）
     */
    @Field(type = FieldType.Keyword)
    private String slug;

    /**
     * 作者資訊
     */
    @Field(type = FieldType.Object)
    private AuthorInfo author;

    /**
     * 文章標籤列表（Nested，支援 Faceted Filter）
     */
    @Field(type = FieldType.Nested)
    private List<TagInfo> tags;

    /**
     * 發布時間
     */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime publishedAt;

    /**
     * 瀏覽次數（Function Score 排序用）
     */
    @Field(type = FieldType.Integer)
    private long viewCount;

    /**
     * 按讚次數（Function Score 排序用）
     */
    @Field(type = FieldType.Integer)
    private long likeCount;

    /**
     * 文章狀態（keyword，僅索引 PUBLISHED）
     */
    @Field(type = FieldType.Keyword)
    private String status;

    /**
     * 作者資訊嵌套物件
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthorInfo {

        /**
         * 作者資料庫主鍵
         */
        @Field(type = FieldType.Keyword)
        private Long id;

        /**
         * 作者帳號名稱
         */
        @Field(type = FieldType.Keyword)
        private String username;

        /**
         * 作者暱稱（用於顯示）
         */
        @Field(type = FieldType.Text)
        private String nickname;
    }

    /**
     * 標籤資訊嵌套物件
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TagInfo {

        /**
         * 標籤公開 UUID
         */
        @Field(type = FieldType.Keyword)
        private UUID id;

        /**
         * 標籤名稱
         */
        @Field(type = FieldType.Keyword)
        private String name;

        /**
         * 標籤 URL slug
         */
        @Field(type = FieldType.Keyword)
        private String slug;
    }
}
