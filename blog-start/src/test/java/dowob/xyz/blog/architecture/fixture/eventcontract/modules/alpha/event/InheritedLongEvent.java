package dowob.xyz.blog.architecture.fixture.eventcontract.modules.alpha.event;

import dowob.xyz.blog.architecture.fixture.eventcontract.modules.alpha.base.LongBase;

import java.util.UUID;

/**
 * EventContractTest 規則 (b) 的反向驗證 fixture——<b>故意違規</b>：Long 欄位在父類別。
 *
 * @author Yuan
 * @version 1.0
 */
public class InheritedLongEvent extends LongBase {

    /** 事件識別（合規） */
    private UUID eventId;
}
