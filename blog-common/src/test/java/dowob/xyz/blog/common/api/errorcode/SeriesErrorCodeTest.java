package dowob.xyz.blog.common.api.errorcode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SeriesErrorCode 單元測試
 *
 * <p>錯誤碼字串屬前端契約：此測試釘住每個常數對應的字串（W7-C3 搬入 blog-common 時字串不得改變），
 * 且訊息不為空。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SeriesErrorCode 單元測試")
class SeriesErrorCodeTest {

    @Test
    @DisplayName("每個常數的錯誤碼字串與搬移前一致（前端契約）")
    void errorCodes_matchFrontendContract() {
        assertThat(Arrays.stream(SeriesErrorCode.values()).map(e -> e.name() + "=" + e.getCode()))
                .containsExactly(
                    "SERIES_NOT_FOUND=S0101",
                    "SERIES_ACCESS_DENIED=S0102",
                    "ARTICLE_NOT_PUBLISHED=S0103",
                    "SLUG_ALREADY_USED=S0104",
                    "ARTICLE_NOT_IN_SERIES=S0105",
                    "ARTICLE_IN_OTHER_SERIES=S0106",
                    "ARTICLE_NOT_FOUND=S0107");
    }

    @Test
    @DisplayName("所有常數的訊息均不為空，且錯誤碼以 S 開頭")
    void allErrorCodes_haveMessageAndPrefix() {
        for (SeriesErrorCode errorCode : SeriesErrorCode.values()) {
            assertThat(errorCode.getMessage())
                    .as("SeriesErrorCode.%s 的 message 不應為空", errorCode.name())
                    .isNotBlank();
            assertThat(errorCode.getCode()).startsWith("S");
        }
    }
}
