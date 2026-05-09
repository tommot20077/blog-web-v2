package dowob.xyz.blog.module.series.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Series 詳情中的「我的進度」子物件。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MyProgress {
    private Integer readCount;
    private Integer totalCount;
    private UUID nextUnreadArticleUuid;
}
