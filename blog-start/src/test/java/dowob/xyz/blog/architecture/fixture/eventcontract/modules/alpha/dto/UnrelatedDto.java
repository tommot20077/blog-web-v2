package dowob.xyz.blog.architecture.fixture.eventcontract.modules.alpha.dto;

/**
 * EventContractTest 的合規對照 fixture：不是事件、也不是 listener 參數的一般 DTO，
 * 有 Long 欄位也不在守衛範圍內，被抓到即為假陽性。
 *
 * @author Yuan
 * @version 1.0
 */
public class UnrelatedDto {

    /** 一般 Long 欄位 */
    private Long id;
}
