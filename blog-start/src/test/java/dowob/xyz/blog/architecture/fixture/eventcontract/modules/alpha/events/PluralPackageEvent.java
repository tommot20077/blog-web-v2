package dowob.xyz.blog.architecture.fixture.eventcontract.modules.alpha.events;

/**
 * EventContractTest 事件集合的反向驗證 fixture——<b>故意違規</b>：
 * 套件名是複數 {@code events}（不是 {@code ..event..}），仍須被視為事件；缺 eventId。
 *
 * @param name 佔位欄位
 * @author Yuan
 * @version 1.0
 */
public record PluralPackageEvent(String name) {
}
