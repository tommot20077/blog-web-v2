package dowob.xyz.blog.common.api.errorcode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReadingErrorCode 單元測試
 *
 * <p>錯誤碼字串屬前端契約：此測試釘住每個常數對應的字串（W7-C3 搬入 blog-common 時字串不得改變），
 * 且訊息不為空。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ReadingErrorCode 單元測試")
class ReadingErrorCodeTest {

    @Test
    @DisplayName("每個常數的錯誤碼字串與搬移前一致（前端契約）")
    void errorCodes_matchFrontendContract() {
        assertThat(Arrays.stream(ReadingErrorCode.values()).map(e -> e.name() + "=" + e.getCode()))
                .containsExactly(
                    "HIGHLIGHT_NOT_FOUND=R0201",
                    "HIGHLIGHT_ACCESS_DENIED=R0202",
                    "PROGRESS_OUT_OF_RANGE=R0301");
    }

    @Test
    @DisplayName("所有常數的訊息均不為空，且錯誤碼以 R 開頭")
    void allErrorCodes_haveMessageAndPrefix() {
        for (ReadingErrorCode errorCode : ReadingErrorCode.values()) {
            assertThat(errorCode.getMessage())
                    .as("ReadingErrorCode.%s 的 message 不應為空", errorCode.name())
                    .isNotBlank();
            assertThat(errorCode.getCode()).startsWith("R");
        }
    }
}
