package dowob.xyz.blog.architecture.fixture.eventcontract.beta;

import dowob.xyz.blog.architecture.fixture.eventcontract.alpha.event.MisplacedCrossModuleEvent;
import dowob.xyz.blog.architecture.fixture.eventcontract.shared.event.SharedEvent;

/**
 * EventContractTest 規則 (c) 的反向驗證 fixture：模組 beta 同時使用
 * 放錯位置的 alpha 事件（違規）與位於共用位置的事件（合規）。
 *
 * @author Yuan
 * @version 1.0
 */
public class BetaConsumer {

    /**
     * 處理放在 alpha 模組內的事件（違規用法）。
     *
     * @param event 放錯位置的跨模組事件
     */
    public void onMisplaced(MisplacedCrossModuleEvent event) {
    }

    /**
     * 處理位於共用契約位置的事件（合規用法）。
     *
     * @param event 共用事件
     */
    public void onShared(SharedEvent event) {
    }
}
