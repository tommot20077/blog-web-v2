package dowob.xyz.blog.architecture.fixture.eventcontract.shared.event;

import java.util.List;

/**
 * EventContractTest 的反向驗證 fixture——<b>故意違規</b>：位於共用契約位置的承載型別
 * （類名不以 Event 結尾，如 {@code TagInfo}），其 Long 欄位仍須被規則 (b) 抓到；
 * 承載型別本身沒有 eventId 是合規的（規則 (a) 只適用於事件本體）。
 *
 * @param ids 違規：{@code List<Long>}
 * @author Yuan
 * @version 1.0
 */
public record PayloadCarrier(List<Long> ids) {
}
