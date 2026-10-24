package dowob.xyz.blog.architecture.fixture.eventcontract.alpha.event;

import java.util.UUID;

/**
 * EventContractTest 的合規對照 fixture：有 {@code UUID eventId}、無 Long 欄位、只被本模組使用。
 * 被守衛抓到即為假陽性。
 *
 * @param eventId 事件識別
 * @param name    佔位欄位
 * @author Yuan
 * @version 1.0
 */
public record CompliantEvent(UUID eventId, String name) {
}
