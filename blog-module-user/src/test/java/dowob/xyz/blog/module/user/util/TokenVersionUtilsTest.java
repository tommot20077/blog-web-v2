package dowob.xyz.blog.module.user.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenVersionUtils 單元測試
 *
 * <p>驗證 token 版本號遞增的所有邊界情況。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class TokenVersionUtilsTest {

    /**
     * 驗證：null 輸入應防禦性地回傳 v1。
     */
    @Test
    @DisplayName("incrementVersion → null 輸入 → 應回傳 v1")
    void incrementVersion_withNullInput_shouldReturnV1() {
        assertThat(TokenVersionUtils.incrementVersion(null)).isEqualTo("v1");
    }

    /**
     * 驗證：v1 應遞增為 v2。
     */
    @Test
    @DisplayName("incrementVersion → v1 → 應回傳 v2")
    void incrementVersion_withV1_shouldReturnV2() {
        assertThat(TokenVersionUtils.incrementVersion("v1")).isEqualTo("v2");
    }

    /**
     * 驗證：格式異常（無 v 前綴、非數字）應防禦性地回傳 v1。
     */
    @Test
    @DisplayName("incrementVersion → 格式異常 → 應回傳 v1")
    void incrementVersion_withInvalidFormat_shouldReturnV1() {
        assertThat(TokenVersionUtils.incrementVersion("invalid")).isEqualTo("v1");
        assertThat(TokenVersionUtils.incrementVersion("vX")).isEqualTo("v1");
        assertThat(TokenVersionUtils.incrementVersion("")).isEqualTo("v1");
    }

    /**
     * 驗證：大數字版本號應正確遞增（v9 → v10）。
     */
    @Test
    @DisplayName("incrementVersion → v9 → 應回傳 v10（多位數進位）")
    void incrementVersion_withLargeNumber_shouldIncrement() {
        assertThat(TokenVersionUtils.incrementVersion("v9")).isEqualTo("v10");
        assertThat(TokenVersionUtils.incrementVersion("v99")).isEqualTo("v100");
    }
}
