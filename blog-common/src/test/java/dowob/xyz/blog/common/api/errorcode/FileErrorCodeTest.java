package dowob.xyz.blog.common.api.errorcode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FileErrorCode 單元測試
 *
 * <p>驗證所有 File 模組錯誤碼均採用 A04xx 命名空間。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("FileErrorCode 單元測試")
class FileErrorCodeTest {

    @Test
    @DisplayName("FILE_NOT_FOUND 錯誤碼應為 A0401")
    void fileNotFound_code_isA0401() {
        assertThat(FileErrorCode.FILE_NOT_FOUND.getCode()).isEqualTo("A0401");
    }

    @Test
    @DisplayName("FILE_TOO_LARGE 錯誤碼應為 A0402")
    void fileTooLarge_code_isA0402() {
        assertThat(FileErrorCode.FILE_TOO_LARGE.getCode()).isEqualTo("A0402");
    }

    @Test
    @DisplayName("QUOTA_EXCEEDED 錯誤碼應為 A0403")
    void quotaExceeded_code_isA0403() {
        assertThat(FileErrorCode.QUOTA_EXCEEDED.getCode()).isEqualTo("A0403");
    }

    @Test
    @DisplayName("INVALID_FILE_TYPE 錯誤碼應為 A0404")
    void invalidFileType_code_isA0404() {
        assertThat(FileErrorCode.INVALID_FILE_TYPE.getCode()).isEqualTo("A0404");
    }

    @Test
    @DisplayName("FILE_ACCESS_DENIED 錯誤碼應為 A0405")
    void fileAccessDenied_code_isA0405() {
        assertThat(FileErrorCode.FILE_ACCESS_DENIED.getCode()).isEqualTo("A0405");
    }
}
