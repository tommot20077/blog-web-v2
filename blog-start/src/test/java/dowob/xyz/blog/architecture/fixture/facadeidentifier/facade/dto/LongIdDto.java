package dowob.xyz.blog.architecture.fixture.facadeidentifier.facade.dto;

import java.util.List;
import java.util.UUID;

/**
 * FacadeIdentifierTypeTest DTO 規則的反向驗證 fixture——<b>故意違規</b>：
 * record component 名稱以 {@code Id}／{@code Ids} 結尾卻是 Long 系型別。
 *
 * @param authorId 違規：包裝型別 Long
 * @param id       違規：原生型別 long
 * @param tagIds   違規：{@code List<Long>}
 * @param uuid     合規：UUID
 * @param count    合規：計數（不以 Id 結尾）
 * @author Yuan
 * @version 1.0
 */
public record LongIdDto(Long authorId, long id, List<Long> tagIds, UUID uuid, long count) {
}
