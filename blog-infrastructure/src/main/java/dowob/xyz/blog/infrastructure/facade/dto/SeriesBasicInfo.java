package dowob.xyz.blog.infrastructure.facade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Series 基本資訊（給 article 列表 enrich 時補 seriesUuid / seriesTitle 用）。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeriesBasicInfo {
    private UUID seriesUuid;
    private String seriesTitle;
}
