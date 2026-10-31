package dowob.xyz.blog.architecture.fixture.facadeidentifier.facade;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * FacadeIdentifierTypeTest 參數規則的反向驗證 fixture——<b>含故意違規</b>：
 * 名稱以 {@code Id}／{@code Ids} 結尾（含 {@code id}、{@code ids}）的參數使用了 Long 系型別。
 *
 * @author Yuan
 * @version 1.0
 */
public interface LongParamFacade {

    /** 違規：包裝型別 Long。 */
    void boxedLong(Long articleId);

    /** 違規：原生型別 long（名稱恰為 {@code id}）。 */
    void primitiveLong(long id);

    /** 違規：{@code List<Long>}。 */
    void listOfLong(List<Long> tagIds);

    /** 違規：{@code Set<Long>}（名稱恰為 {@code ids}）。 */
    void setOfLong(Set<Long> ids);

    /** 違規：{@code Collection<Long>}。 */
    void collectionOfLong(Collection<Long> userIds);

    /** 違規：Long 陣列。 */
    void arrayOfLong(Long[] seriesIds);

    /** 合規：UUID 識別；{@code limit}／{@code total}／{@code count} 不以 Id 結尾，Long 系型別不在此限。 */
    void compliant(UUID articleUuid, List<UUID> tagIds, UUID id, long limit, Long total, int count);

    /** 合規：名稱以 {@code Uuid}／{@code Uuids} 結尾，且為 UUID 集合。 */
    void compliantUuids(UUID userUuid, Collection<UUID> articleUuids);
}
