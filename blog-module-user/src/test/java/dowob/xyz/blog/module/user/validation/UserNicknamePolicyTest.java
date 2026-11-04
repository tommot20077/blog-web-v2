package dowob.xyz.blog.module.user.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserNicknamePolicy} 單元測試：保留「已刪除使用者」前綴。
 */
class UserNicknamePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "已刪除使用者",
            "已刪除使用者#1a2b3c4d",
            "  已刪除使用者#1a2b3c4d",
            "已刪除使用者 #1a2b3c4d",
            "已刪除使用者　#1a2b3c4d",
            "已刪除 使用者",
            "已刪除　使用者",
            "已刪除​使用者"
    })
    @DisplayName("isReserved → 前綴（含半形／全形空白、零寬字元變體）→ true")
    void isReserved_prefixVariants_returnsTrue(String nickname) {
        assertThat(UserNicknamePolicy.isReserved(nickname)).isTrue();
    }

    @Test
    @DisplayName("isReserved → 一般暱稱、含前綴字樣但非開頭、null → false")
    void isReserved_normalNicknames_returnsFalse() {
        assertThat(UserNicknamePolicy.isReserved("Yuan")).isFalse();
        assertThat(UserNicknamePolicy.isReserved("我是已刪除使用者")).isFalse();
        assertThat(UserNicknamePolicy.isReserved(null)).isFalse();
        assertThat(UserNicknamePolicy.isReserved("")).isFalse();
    }
}
