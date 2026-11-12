package dowob.xyz.blog.architecture.fixture;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 守衛 #7 的反向驗證 fixture——<b>故意違規</b>，不得被當成範例。
 *
 * <p>存在理由同 {@link TransactionalMqViolationFixture}：只斷言 violations 為空
 * 無法區分「真的沒違規」與「守衛壞了」。</p>
 *
 * <p>類名以 {@code Controller} 結尾是必要的——守衛以此篩選掃描對象。
 * 本類位於測試 source，正向守衛只匯入 {@code dowob.xyz.blog.module}，不會掃到。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
public class UnauthorizedEndpointFixture {

    /**
     * 違規：取用 {@code @AuthenticationPrincipal} 卻無任何方法層授權標註。
     *
     * @param userId 當前登入者主鍵
     * @return 佔位字串
     */
    @GetMapping("/fixture/unauthorized")
    public String usesPrincipalWithoutPreAuthorize(@AuthenticationPrincipal Long userId) {
        return String.valueOf(userId);
    }

    /**
     * 合規對照：有 {@code @PreAuthorize}。
     *
     * @param userId 當前登入者主鍵
     * @return 佔位字串
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/fixture/authorized")
    public String usesPrincipalWithPreAuthorize(@AuthenticationPrincipal Long userId) {
        return String.valueOf(userId);
    }

    /**
     * 合規對照：非 handler 方法（無 mapping 標註），不在守衛掃描範圍。
     *
     * @param userId 當前登入者主鍵
     * @return 佔位字串
     */
    public String notAHandler(@AuthenticationPrincipal Long userId) {
        return String.valueOf(userId);
    }
}
