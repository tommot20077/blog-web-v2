package dowob.xyz.blog.common.api.errorcode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserErrorCode} 單元測試：錯誤碼不重複，且已停用的 A0106 不再出現。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("UserErrorCode")
class UserErrorCodeTest {

    @Test
    @DisplayName("A0106（EMAIL_DUPLICATED）已停用（Q8，2026-11-04）：不在列舉中，也不得被其他常數重用")
    void retiredEmailDuplicatedCode_isNotPresentAndNotReused() {
        assertThat(Arrays.stream(UserErrorCode.values()).map(Enum::name)).doesNotContain("EMAIL_DUPLICATED");
        assertThat(Arrays.stream(UserErrorCode.values()).map(UserErrorCode::getCode)).doesNotContain("A0106");
    }

    @Test
    @DisplayName("所有錯誤碼互不重複")
    void codes_areUnique() {
        assertThat(Arrays.stream(UserErrorCode.values()).map(UserErrorCode::getCode)).doesNotHaveDuplicates();
    }
}
