package dowob.xyz.blog.architecture.fixture.eventcontract.modules.alpha.event;

/**
 * EventContractTest 規則 (a) 的反向驗證 fixture——<b>故意違規</b>：事件缺少 {@code UUID eventId}。
 *
 * <p>位於測試 source，正向守衛只匯入正式碼，不會掃到。</p>
 *
 * @param name 佔位欄位
 * @author Yuan
 * @version 1.0
 */
public record NoEventIdEvent(String name) {
}
