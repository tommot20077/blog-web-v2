package dowob.xyz.blog.architecture.fixture.facadeidentifier.facade;

import dowob.xyz.blog.architecture.fixture.facadeidentifier.facade.dto.CompliantDto;
import dowob.xyz.blog.architecture.fixture.facadeidentifier.facade.dto.LongIdDto;
import dowob.xyz.blog.architecture.fixture.facadeidentifier.facade.dto.NestedHolderDto;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
}
