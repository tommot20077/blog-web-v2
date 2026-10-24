package dowob.xyz.blog.architecture.fixture.eventcontract.shared.event;

import java.util.UUID;

/**
 * EventContractTest 的合規對照 fixture：代表位於共用契約位置的跨模組事件，
 * 被他模組使用不算違規。
 *
 * @param eventId 事件識別
 * @author Yuan
 * @version 1.0
 */
public record SharedEvent(UUID eventId) {
}
