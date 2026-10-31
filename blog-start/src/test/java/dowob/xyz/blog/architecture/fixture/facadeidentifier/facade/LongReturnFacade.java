package dowob.xyz.blog.architecture.fixture.facadeidentifier.facade;

import dowob.xyz.blog.architecture.fixture.facadeidentifier.facade.dto.CompliantDto;
import dowob.xyz.blog.architecture.fixture.facadeidentifier.facade.dto.LongIdDto;
import dowob.xyz.blog.architecture.fixture.facadeidentifier.facade.dto.NestedHolderDto;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * FacadeIdentifierTypeTest 回傳規則的反向驗證 fixture——<b>含故意違規</b>：
 * 回傳 {@code Map<Long, …>}，或回傳含 Long 識別欄位的 DTO（含包在 List／Optional／Map value 裡）。
 *
 * @author Yuan
 * @version 1.0
 */
public interface LongReturnFacade {

    /** 違規：Map 的 key 是 Long。 */
    Map<Long, String> keyedByLong();

    /** 違規：Map 的 key 是 Long，且被包在 Optional 裡。 */
    Optional<Map<Long, UUID>> optionalKeyedByLong();

    /** 違規：回傳的 DTO 含 Long 識別欄位。 */
    LongIdDto dtoWithLongId();

    /** 違規：DTO 被包在 List 裡。 */
    List<LongIdDto> listOfDtoWithLongId();

    /** 違規：DTO 被包在 Optional 裡。 */
    Optional<LongIdDto> optionalDtoWithLongId();

    /** 違規：DTO 被包在 Map value 裡。 */
    Map<UUID, LongIdDto> mapValueDtoWithLongId();

    /** 違規：DTO 的巢狀 DTO 欄位含 Long 識別。 */
    NestedHolderDto nestedDtoWithLongId();

    /** 合規：Map value 是計數 Long（key 為 UUID）。 */
    Map<UUID, Long> countByUuid();

    /** 合規：DTO 的 Long 欄位只有計數類。 */
    CompliantDto compliantDto();

    /** 合規：非識別的 Long 回傳（計數）。 */
    long countAll();

    /** 違規：方法名稱有獨立的 Ids token，回傳 {@code List<Long>}。 */
    List<Long> findTagIdsByArticleUuid(UUID articleUuid);

    /** 違規：方法名稱有獨立的 Id token，回傳包裝型別 Long。 */
    Long findIdByUuid(UUID uuid);

    /** 違規：回傳 {@code Optional<Long>}。 */
    Optional<Long> findOptionalId(UUID uuid);

    /** 違規：回傳 {@code Set<Long>}（方法名稱首個 token 是小寫 ids）。 */
    Set<Long> idsOf(UUID uuid);

    /** 違規：回傳 {@code long[]}。 */
    long[] listIds();

    /** 違規：回傳原生型別 long，名稱含 ID token（全大寫）。 */
    long findByUserID(UUID uuid);

    /** 合規：計數，名稱沒有 Id token。 */
    long countPublished(UUID seriesUuid);

    /** 合規：回傳 UUID 集合（名稱雖有 Ids token，但型別不是 Long）。 */
    Set<UUID> filterExistingUuids(Collection<UUID> uuids);

    /** 合規：名稱有 Ids token 但回傳 UUID 清單。 */
    List<UUID> findTagIds();

    /** 合規：Valid 不是 Id token，回傳 Long 不受影響。 */
    Long getValidTotal();

    /** 合規：Idle 不是 Id token。 */
    long idleCount();
}
