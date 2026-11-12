package dowob.xyz.blog.architecture.fixture.eventcontract.modules.alpha.dto;

/**
 * EventContractTest 事件集合的反向驗證 fixture——<b>故意違規</b>：
 * 類名不以 Event 結尾，但是 {@code @RabbitListener} 的訊息參數（如 search 的鏡像 DTO）；缺 eventId 且有 Long。
 *
 * @author Yuan
 * @version 1.0
 */
public class ListenerMessage {

    /** 違規：Long 識別 */
    private Long authorId;
}
