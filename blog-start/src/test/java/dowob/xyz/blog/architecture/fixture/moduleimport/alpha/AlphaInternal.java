package dowob.xyz.blog.architecture.fixture.moduleimport.alpha;

/**
 * CrossModuleImportTest 的合規對照 fixture：同模組內依賴 {@link AlphaTarget} 不算違規。
 *
 * @author Yuan
 * @version 1.0
 */
public class AlphaInternal {

    private final AlphaTarget target = new AlphaTarget();

    /**
     * 回傳同模組的依賴。
     *
     * @return 同模組類別
     */
    public AlphaTarget target() {
        return target;
    }
}
