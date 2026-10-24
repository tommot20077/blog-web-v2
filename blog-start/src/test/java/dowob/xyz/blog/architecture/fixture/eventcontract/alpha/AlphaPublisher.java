package dowob.xyz.blog.architecture.fixture.eventcontract.alpha;

import dowob.xyz.blog.architecture.fixture.eventcontract.alpha.event.CompliantEvent;

import java.util.UUID;

/**
 * EventContractTest 的合規對照 fixture：只在模組內部使用 {@link CompliantEvent}，
 * 模組內部事件不受規則 (c) 約束。
 *
 * @author Yuan
 * @version 1.0
 */
public class AlphaPublisher {

    /**
     * 建立模組內部事件。
     *
     * @return 內部事件
     */
    public CompliantEvent publish() {
        return new CompliantEvent(UUID.randomUUID(), "internal");
    }
}
