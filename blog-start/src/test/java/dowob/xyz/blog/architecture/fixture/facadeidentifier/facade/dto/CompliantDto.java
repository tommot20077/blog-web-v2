package dowob.xyz.blog.architecture.fixture.facadeidentifier.facade.dto;

import java.util.List;
import java.util.UUID;

/**
 * FacadeIdentifierTypeTest 反向驗證 fixture——<b>合規</b>：被判違規即為假陽性。
 *
 * @param id       UUID 識別
 * @param tagIds   UUID 集合
 * @param total    計數（Long，名稱不以 Id 結尾）
 * @param viewCount 計數（long）
 * @author Yuan
 * @version 1.0
 */
public record CompliantDto(UUID id, List<UUID> tagIds, Long total, long viewCount) {
}
