package dowob.xyz.blog.module.series.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Series 與作者 JOIN 後的扁平 row（MyBatis 用）。
 *
 * <p>用 map-underscore-to-camel-case 自動對應，以下欄位都從 SQL alias 來：</p>
 * <ul>
 *   <li>series 自身欄位（id, uuid, title, slug, ...）</li>
 *   <li>author_uuid → authorUuid / author_nickname → authorNickname / author_avatar_url → authorAvatarUrl</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class SeriesWithAuthor {
    private Long id;
    private UUID uuid;
    private String title;
    private String slug;
    private String description;
    private String coverImageUrl;
    private Long authorId;
    private Integer articleCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private UUID authorUuid;
    private String authorNickname;
    private String authorAvatarUrl;
}
