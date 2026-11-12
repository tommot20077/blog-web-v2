package dowob.xyz.blog.architecture.fixture.eventcontract.modules.alpha.event;

import java.util.UUID;

/**
 * EventContractTest 規則 (c) 的反向驗證 fixture——<b>故意違規</b>：
 * 事件位於模組 alpha 之下，卻沒有登記為「模組內部事件」，
 * 即新增了未經確認的模組內事件（可能是應放共用契約位置的跨模組事件）。
 *
 * @param eventId 事件識別
 * @author Yuan
 * @version 1.0
 */
public record UnregisteredModuleEvent(UUID eventId) {
}
