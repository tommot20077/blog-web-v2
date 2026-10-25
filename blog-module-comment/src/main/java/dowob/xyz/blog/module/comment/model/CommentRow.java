package dowob.xyz.blog.module.comment.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 留言列表查詢的結果列（{@code CommentMapper} 的 SELECT 投影）。
 *
 * <p>原名 {@code CommentWithAuthor}：舊版以 LEFT JOIN 使用者表一併取回作者暱稱與頭像。
 * 跨模組識別全面改為 UUID 之後，comment 模組不再 JOIN 他模組的表，作者資訊改由
 * {@code CommentService} 收集整頁的 {@code userUuid}、呼叫 {@code UserFacade.getDisplayInfoByUuids}
 * 一次批次取得後組裝，因此本類別只剩留言自己的欄位，也不再叫 WithAuthor。</p>
 *
 * <p>對應 CommentMapper 的 SELECT 結果欄位（snake_case → camelCase 由 MyBatis underscore-to-camel 自動映射）。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Data
public class CommentRow {
    private Long id;
    private UUID uuid;

    /** 所屬文章的公開 UUID（跨模組識別） */
    private UUID articleUuid;

    /** 父留言主鍵（模組內部關聯；頂層為 null） */
    private Long parentId;

    /** 由 self-join 子查詢取得（reply 帶 parent uuid 用於前端表達） */
    private UUID parentUuid;

    /** 留言者的公開 UUID（跨模組識別） */
    private UUID userUuid;

    private String content;
    private String contentHtml;
    private Integer likeCount;
    private LocalDateTime editedAt;
    private LocalDateTime deletedAt;
    private String deletedByRole;
    private LocalDateTime createdAt;
}
