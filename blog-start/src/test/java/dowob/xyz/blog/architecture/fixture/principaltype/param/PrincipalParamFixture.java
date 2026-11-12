package dowob.xyz.blog.architecture.fixture.principaltype.param;

import dowob.xyz.blog.common.security.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * {@code PrincipalTypeTest} 規則 (a) 的反向驗證 fixture——<b>含故意違規</b>，不得被當成範例。
 *
 * <p>守衛若抓不到這裡的違規方法，「正式碼掃描無違規」就無法區分「真的沒違規」與「守衛壞了」。
 * 本類位於測試 source，正式碼掃描（{@code DO_NOT_INCLUDE_TESTS}）不會掃到。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class PrincipalParamFixture {

    /**
     * 違規：principal 為 {@code Long}（T0b-3 之前的舊寫法）。
     *
     * @param userId 當前登入者主鍵
     * @return 佔位字串
     */
    public String boxedLong(@AuthenticationPrincipal Long userId) {
        return String.valueOf(userId);
    }

    /**
     * 違規：principal 為原生 {@code long}。
     *
     * @param userId 當前登入者主鍵
     * @return 佔位字串
     */
    public String primitiveLong(@AuthenticationPrincipal long userId) {
        return String.valueOf(userId);
    }

    /**
     * 違規：principal 為 {@code Object}（型別不是 AuthUser 就不行，寬鬆型別也不例外）。
     *
     * @param principal 當前登入者
     * @return 佔位字串
     */
    public String looseObject(@AuthenticationPrincipal Object principal) {
        return String.valueOf(principal);
    }

    /**
     * 違規：@AuthenticationPrincipal 不在第一個參數，索引要正確標出。
     *
     * @param name   無關參數
     * @param userId 當前登入者主鍵
     * @return 佔位字串
     */
    public String secondParameter(String name, @AuthenticationPrincipal Long userId) {
        return name + userId;
    }

    /**
     * 合規對照：型別為 {@link AuthUser}。
     *
     * @param user 當前登入者
     * @return 佔位字串
     */
    public String compliant(@AuthenticationPrincipal AuthUser user) {
        return String.valueOf(user);
    }

    /**
     * 合規對照：Long 參數但<b>沒有</b> {@code @AuthenticationPrincipal}，不在守衛範圍。
     *
     * @param userId 一般 Long 參數
     * @return 佔位字串
     */
    public String unannotatedLong(Long userId) {
        return String.valueOf(userId);
    }
}
