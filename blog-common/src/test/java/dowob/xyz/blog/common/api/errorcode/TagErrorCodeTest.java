package dowob.xyz.blog.common.api.errorcode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TagErrorCode 單元測試
 *
 * <p>驗證所有標籤模組錯誤碼均採用 A03xx 命名空間，且訊息非空。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TagErrorCode 單元測試")
class TagErrorCodeTest {

    @Test
    @DisplayName("TAG_NOT_FOUND 錯誤碼應為 A0301，訊息不為空")
    void tagNotFound_codeIsA0301() {
        assertThat(TagErrorCode.TAG_NOT_FOUND.getCode()).isEqualTo("A0301");
        assertThat(TagErrorCode.TAG_NOT_FOUND.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("TAG_NAME_CONFLICT 錯誤碼應為 A0302")
    void tagNameConflict_codeIsA0302() {
        assertThat(TagErrorCode.TAG_NAME_CONFLICT.getCode()).isEqualTo("A0302");
        assertThat(TagErrorCode.TAG_NAME_CONFLICT.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("TAG_IN_USE 錯誤碼應為 A0303")
    void tagInUse_codeIsA0303() {
        assertThat(TagErrorCode.TAG_IN_USE.getCode()).isEqualTo("A0303");
        assertThat(TagErrorCode.TAG_IN_USE.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("TAG_SLUG_CONFLICT 錯誤碼應為 A0304")
    void tagSlugConflict_codeIsA0304() {
        assertThat(TagErrorCode.TAG_SLUG_CONFLICT.getCode()).isEqualTo("A0304");
        assertThat(TagErrorCode.TAG_SLUG_CONFLICT.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("TAG_INVALID_NAME 錯誤碼應為 A0305")
    void tagInvalidName_codeIsA0305() {
        assertThat(TagErrorCode.TAG_INVALID_NAME.getCode()).isEqualTo("A0305");
        assertThat(TagErrorCode.TAG_INVALID_NAME.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("所有 TagErrorCode 均實作 IErrorCode 介面，code 與 message 均不為空")
    void allTagErrorCodes_implementIErrorCode_withNonBlankFields() {
        for (TagErrorCode errorCode : TagErrorCode.values()) {
            assertThat(errorCode.getCode())
                    .as("TagErrorCode.%s 的 code 不應為空", errorCode.name())
                    .isNotBlank();
            assertThat(errorCode.getMessage())
                    .as("TagErrorCode.%s 的 message 不應為空", errorCode.name())
                    .isNotBlank();
            assertThat(errorCode.getCode())
                    .as("TagErrorCode.%s 的 code 應以 A03 開頭", errorCode.name())
                    .startsWith("A03");
        }
    }
}
