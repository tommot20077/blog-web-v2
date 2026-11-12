package dowob.xyz.blog.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PasswordPolicy} 單元測試
 *
 * <p>驗證密碼複雜度規則：須同時包含小寫字母、大寫字母、數字與特殊字元，
 * 且長度為 {@value PasswordPolicy#MIN_LENGTH}-{@value PasswordPolicy#MAX_LENGTH} 字元。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@DisplayName("PasswordPolicy 單元測試")
class PasswordPolicyTest {

    /** 預編譯的密碼複雜度 Pattern，供各案例重複使用 */
    private static final Pattern COMPILED = Pattern.compile(PasswordPolicy.PATTERN);

    /** 以單一字元重複建構指定長度的合法密碼骨架（已含四類字元） */
    private static String passwordOfLength(int length) {
        // 前 4 字元固定提供小寫/大寫/數字/特殊各一，其餘以 'a' 填滿
        String prefix = "Aa1!";
        if (length <= prefix.length()) {
            return prefix.substring(0, length);
        }
        return prefix + "a".repeat(length - prefix.length());
    }

    // =========================================================================
    // 常數值
    // =========================================================================

    @Test
    @DisplayName("MIN_LENGTH 應為 8")
    void minLength_shouldBe8() {
        assertThat(PasswordPolicy.MIN_LENGTH).isEqualTo(8);
    }

    @Test
    @DisplayName("MAX_LENGTH 應為 50")
    void maxLength_shouldBe50() {
        assertThat(PasswordPolicy.MAX_LENGTH).isEqualTo(50);
    }

    @Test
    @DisplayName("PATTERN_MESSAGE 應提及四類字元要求")
    void patternMessage_shouldMentionAllCharacterClasses() {
        assertThat(PasswordPolicy.PATTERN_MESSAGE)
                .contains("小寫")
                .contains("大寫")
                .contains("數字")
                .contains("特殊字元")
                .contains("8")
                .contains("50");
    }

    // =========================================================================
    // 合法案例
    // =========================================================================

    @ParameterizedTest(name = "合法密碼「{0}」應通過驗證")
    @ValueSource(strings = {
            "Aa1!aaaa",       // 邊界：長度 8，四類字元齊全
            "Password123!",   // 一般情境
            "Abcd123#@$",     // 多個特殊字元（皆在允許集合內）
            "MyPass99&word"   // 較長且四類齊全
    })
    @DisplayName("含小寫+大寫+數字+特殊字元且長度合法的密碼應通過")
    void validPasswords_shouldMatch(String password) {
        assertThat(COMPILED.matcher(password).matches())
                .as("密碼「%s」應符合複雜度規則", password)
                .isTrue();
    }

    @Test
    @DisplayName("邊界：長度 8 的合法密碼應通過")
    void minBoundaryLength_shouldMatch() {
        String password = passwordOfLength(8);
        assertThat(password).hasSize(8);
        assertThat(COMPILED.matcher(password).matches()).isTrue();
    }

    @Test
    @DisplayName("邊界：長度 50 的合法密碼應通過")
    void maxBoundaryLength_shouldMatch() {
        String password = passwordOfLength(50);
        assertThat(password).hasSize(50);
        assertThat(COMPILED.matcher(password).matches()).isTrue();
    }

    // =========================================================================
    // 不合法案例
    // =========================================================================

    @Test
    @DisplayName("缺大寫字母應失敗")
    void missingUppercase_shouldNotMatch() {
        assertThat(COMPILED.matcher("password123!").matches()).isFalse();
    }

    @Test
    @DisplayName("缺小寫字母應失敗")
    void missingLowercase_shouldNotMatch() {
        assertThat(COMPILED.matcher("PASSWORD123!").matches()).isFalse();
    }

    @Test
    @DisplayName("缺數字應失敗")
    void missingDigit_shouldNotMatch() {
        assertThat(COMPILED.matcher("Password!!!").matches()).isFalse();
    }

    @Test
    @DisplayName("缺特殊字元應失敗（舊規則允許但新規則拒絕）")
    void missingSpecialChar_shouldNotMatch() {
        assertThat(COMPILED.matcher("Password123").matches()).isFalse();
    }

    @Test
    @DisplayName("特殊字元不在允許集合內應失敗")
    void specialCharOutsideAllowedSet_shouldNotMatch() {
        // '^' 不在允許集合 @$!%*?&# 內，且無其他允許的特殊字元
        assertThat(COMPILED.matcher("Password123^").matches()).isFalse();
    }

    @Test
    @DisplayName("邊界：長度 7 的密碼（四類齊全）應失敗")
    void belowMinLength_shouldNotMatch() {
        String password = passwordOfLength(7);
        assertThat(password).hasSize(7);
        assertThat(COMPILED.matcher(password).matches()).isFalse();
    }

    @Test
    @DisplayName("邊界：長度 51 的密碼（四類齊全）應失敗")
    void aboveMaxLength_shouldNotMatch() {
        String password = passwordOfLength(51);
        assertThat(password).hasSize(51);
        assertThat(COMPILED.matcher(password).matches()).isFalse();
    }
}
