package dowob.xyz.blog.architecture.fixture.eventcontract.modules.alpha.event;

import java.util.UUID;

/**
 * EventContractTest 規則 (a)(b) 的反向驗證 fixture：只有 static 欄位。
 *
 * <p>static 的 {@code UUID eventId} 不算事件欄位，不得騙過規則 (a)（故應被抓為缺 eventId）；
 * static 的 {@code long serialVersionUID} 不是事件內容，不得被規則 (b) 誤判。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class StaticFieldEvent {

    /** 序列化版本（static，不該被規則 (b) 誤判） */
    static final long serialVersionUID = 1L;

    /** 靜態欄位（不得騙過規則 (a)） */
    static UUID eventId;
}
