package dowob.xyz.blog.architecture.fixture.eventcontract.alpha.event;

import java.util.UUID;

/**
 * EventContractTest 規則 (c) 的反向驗證 fixture——<b>故意違規</b>：
 * 事件位於模組 alpha，卻被模組 beta 的類別使用（見 {@code BetaConsumer}），
 * 即跨模組事件沒有放在共用契約位置。
 *
 * @param eventId 事件識別
 * @author Yuan
 * @version 1.0
 */
public record MisplacedCrossModuleEvent(UUID eventId) {
}
