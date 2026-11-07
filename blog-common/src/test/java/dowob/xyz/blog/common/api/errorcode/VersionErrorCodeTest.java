package dowob.xyz.blog.common.api.errorcode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VersionErrorCode 單元測試
 *
 * <p>錯誤碼字串屬前端契約：此測試釘住每個常數對應的字串（W7-C3 搬入 blog-common 時字串不得改變），
 * 且訊息不為空。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("VersionErrorCode 單元測試")
class VersionErrorCodeTest {

    @Test
    @DisplayName("每個常數的錯誤碼字串與搬移前一致（前端契約）")
    void errorCodes_matchFrontendContract() {
        assertThat(Arrays.stream(VersionErrorCode.values()).map(e -> e.name() + "=" + e.getCode()))
                .containsExactly(
                    "VERSION_NOT_FOUND=V0101",
                    "VERSION_ACCESS_DENIED=V0102",
                    "CANNOT_DELETE_PUBLISHED=V0103",
                    "CANNOT_PROMOTE_NON_AUTO=V0104",
                    "PREFERENCE_INVALID=V0105",
                    "ARTICLE_NOT_FOUND=V0106",
                    "INVALID_PREFERENCE_KEY=V0107",
                    "VERSION_ARTICLE_MISMATCH=V0108");
    }

    @Test
    @DisplayName("所有常數的訊息均不為空，且錯誤碼以 V 開頭")
    void allErrorCodes_haveMessageAndPrefix() {
        for (VersionErrorCode errorCode : VersionErrorCode.values()) {
            assertThat(errorCode.getMessage())
                    .as("VersionErrorCode.%s 的 message 不應為空", errorCode.name())
                    .isNotBlank();
            assertThat(errorCode.getCode()).startsWith("V");
        }
    }
}
