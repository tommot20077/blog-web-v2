package dowob.xyz.blog.architecture.fixture.eventcontract.modules.alpha.event;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * EventContractTest 規則 (b) 的反向驗證 fixture——<b>故意違規</b>：
 * Long 藏在泛型、陣列與 Optional 裡（只看欄位原始型別會漏抓）。
 *
 * @param eventId 事件識別（合規）
 * @param list    違規：{@code List<Long>}
 * @param set     違規：{@code Set<Long>}
 * @param array   違規：{@code Long[]}
 * @param primArr 違規：{@code long[]}
 * @param map     違規：{@code Map<Long, String>}
 * @param opt     違規：{@code Optional<Long>}
 * @param ok      合規：{@code List<UUID>}
 * @author Yuan
 * @version 1.0
 */
public record GenericLongEvent(UUID eventId, List<Long> list, Set<Long> set, Long[] array, long[] primArr,
                               Map<Long, String> map, Optional<Long> opt, List<UUID> ok) {
}
