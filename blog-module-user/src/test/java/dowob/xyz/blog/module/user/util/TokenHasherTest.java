package dowob.xyz.blog.module.user.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TokenHasher} 單元測試（A1：verification_tokens 只存 SHA-256）
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TokenHasher 單元測試")
class TokenHasherTest {

    @Test
    @DisplayName("sha256Hex：對照已知向量（abc），輸出 64 字元小寫 hex")
    void sha256Hex_knownVector() {
        assertThat(TokenHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("sha256Hex：同輸入同輸出、不同輸入不同輸出，且輸出不等於輸入")
    void sha256Hex_deterministicAndDistinct() {
        String token = "3f2a9c1e-7b44-4d1c-9a0e-5c1d2e3f4a5b";

        assertThat(TokenHasher.sha256Hex(token)).isEqualTo(TokenHasher.sha256Hex(token)).isNotEqualTo(token);
        assertThat(TokenHasher.sha256Hex(token)).isNotEqualTo(TokenHasher.sha256Hex(token + "x"));
        assertThat(TokenHasher.sha256Hex(token)).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("sha256Hex：對雜湊再雜湊得到另一個值（用雜湊本身當 token 查詢不會命中）")
    void sha256Hex_hashOfHashDiffers() {
        String hash = TokenHasher.sha256Hex("some-token");

        assertThat(TokenHasher.sha256Hex(hash)).isNotEqualTo(hash);
    }

    @Test
    @DisplayName("sha256Hex：null 輸入拋 NullPointerException，不回傳「null 的雜湊」")
    void sha256Hex_nullInput_throws() {
        assertThatThrownBy(() -> TokenHasher.sha256Hex(null)).isInstanceOf(NullPointerException.class);
    }
}
