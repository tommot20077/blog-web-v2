package dowob.xyz.blog.common.api.errorcode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SearchErrorCode 單元測試
 *
 * <p>驗證搜尋模組錯誤碼採用 A05xx 命名空間（依 A01 user、A02 article、A03 tag、A04 file 的序列），且訊息非空。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SearchErrorCode 單元測試")
class SearchErrorCodeTest {

    @Test
    @DisplayName("REINDEX_IN_PROGRESS 錯誤碼應為 A0501，訊息不為空")
    void reindexInProgress_codeIsA0501() {
        assertThat(SearchErrorCode.REINDEX_IN_PROGRESS.getCode()).isEqualTo("A0501");
        assertThat(SearchErrorCode.REINDEX_IN_PROGRESS.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("所有 SearchErrorCode 的 code 以 A05 開頭、message 不為空")
    void allSearchErrorCodes_useA05Namespace() {
        for (SearchErrorCode errorCode : SearchErrorCode.values()) {
            assertThat(errorCode.getCode()).as("SearchErrorCode.%s", errorCode.name()).startsWith("A05");
            assertThat(errorCode.getMessage()).as("SearchErrorCode.%s", errorCode.name()).isNotBlank();
        }
    }
}
