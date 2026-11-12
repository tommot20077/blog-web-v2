package dowob.xyz.blog.architecture.fixture.moduleimport.beta;

import dowob.xyz.blog.architecture.fixture.moduleimport.alpha.AlphaTarget;

/**
 * CrossModuleImportTest 的反向驗證 fixture——<b>故意違規</b>：模組 beta 直接依賴模組 alpha 的類別。
 *
 * @author Yuan
 * @version 1.0
 */
public class BetaViolator {

    /**
     * 透過欄位型別與方法參數直接依賴他模組。
     *
     * @param target 他模組的類別
     * @return 原物件
     */
    public AlphaTarget pass(AlphaTarget target) {
        return target;
    }
}
