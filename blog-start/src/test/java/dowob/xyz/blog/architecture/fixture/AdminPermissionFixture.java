package dowob.xyz.blog.architecture.fixture;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 守衛「admin 端點必須用 ADMIN_* permission」的反向驗證 fixture——<b>故意違規</b>，不得被當成範例（A9）。
 *
 * <p>類名以 {@code Controller} 或 {@code Fixture} 結尾皆會被守衛納入；本類位於測試 source，
 * 正向守衛只匯入 {@code dowob.xyz.blog.module}，不會掃到。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/admin/fixture")
public class AdminPermissionFixture {

    /**
     * 違規：admin 端點沿用已廢除的泛用 permission。
     *
     * @return 佔位字串
     */
    @PreAuthorize("hasAuthority('SYSTEM_CONFIG')")
    @GetMapping("/legacy")
    public String legacyGenericPermission() {
        return "x";
    }

    /**
     * 違規：admin 端點完全沒有方法層授權。
     *
     * @return 佔位字串
     */
    @GetMapping("/bare")
    public String missingPreAuthorize() {
        return "x";
    }

    /**
     * 違規：admin 端點只要求登入（不是 ADMIN_* permission）。
     *
     * @return 佔位字串
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/authenticated-only")
    public String authenticatedOnly() {
        return "x";
    }

    /**
     * 違規：引用不存在的 permission 名稱（拼錯或已改名）。
     *
     * @return 佔位字串
     */
    @PreAuthorize("hasAuthority('ADMIN_TAGG')")
    @GetMapping("/typo")
    public String unknownPermission() {
        return "x";
    }

    /**
     * 合規對照：admin 端點使用存在的 ADMIN_* permission。
     *
     * @return 佔位字串
     */
    @PreAuthorize("hasAuthority('ADMIN_TAG')")
    @GetMapping("/ok")
    public String compliant() {
        return "x";
    }
}
