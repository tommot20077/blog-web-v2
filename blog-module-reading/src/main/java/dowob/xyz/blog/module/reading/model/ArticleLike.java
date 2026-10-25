package dowob.xyz.blog.module.reading.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文章按讚紀錄（user_article_likes 表映射）。
 *
 * <p>採用 surrogate id + UNIQUE(user_uuid, article_uuid) 確保使用者對單一文章只能按一次讚。</p>
 *
 * <p>V15 migration 將表名從 article_likes RENAME 為 user_article_likes，
 * 並從 article 模組搬到 reading 模組統一管理。V26 起使用者與文章一律以 UUID 識別，
 * 不再有指向 users／articles 的 Long 欄位與 FK。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_article_likes")
public class ArticleLike {
    @Id
    private Long id;

    /** 按讚／收藏者的公開 UUID（跨模組識別；無 FK） */
    @Column("user_uuid")
    private UUID userUuid;

    /** 文章公開 UUID（跨模組識別；無 FK，文章刪除後由事件＋每日校正排程清除） */
    @Column("article_uuid")
    private UUID articleUuid;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;
}
