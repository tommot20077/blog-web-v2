package dowob.xyz.blog.architecture.fixture.eventcontract.alpha.event;

import java.util.UUID;

/**
 * EventContractTest 規則 (b) 的反向驗證 fixture——<b>故意違規</b>：
 * 事件同時含 {@code Long} 與 {@code long} 識別欄位（record component）。
 *
 * @param eventId   事件識別（合規）
 * @param articleId 違規：包裝型別 Long
 * @param count     違規：原生型別 long
 * @param label     合規：非 Long 欄位
 * @author Yuan
 * @version 1.0
 */
public record LongFieldEvent(UUID eventId, Long articleId, long count, String label) {
}
