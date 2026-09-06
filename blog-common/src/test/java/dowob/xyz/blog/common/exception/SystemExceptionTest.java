package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemException 單元測試
 *
 * <p>
 * 驗證 {@link SystemException} 正確繼承 {@link BaseException}，
 * 並完整保存 {@link dowob.xyz.blog.common.api.errorcode.IErrorCode} 物件。
 * 系統異常代表 B 類 / C 類錯誤（非使用者預期行為）。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SystemException 單元測試")
class SystemExceptionTest {

    /**
     * 驗證以 IErrorCode 建構時，errorCode 物件正確保存，getCode() 委派給 errorCode
     */
    @Test
    @DisplayName("以 IErrorCode 建構時，正確儲存錯誤碼物件並提供 getCode()")
    void whenConstructedWithIErrorCode_storesCodeAndErrorCode() {
        SystemException ex = new SystemException(CommonErrorCode.SYSTEM_EXECUTION_ERROR);

        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.SYSTEM_EXECUTION_ERROR);
        assertThat(ex.getCode()).isEqualTo(CommonErrorCode.SYSTEM_EXECUTION_ERROR.getCode());
        assertThat(ex.getMessage()).isEqualTo(CommonErrorCode.SYSTEM_EXECUTION_ERROR.getMessage());
    }

    /**
     * 驗證以 IErrorCode + 自定義訊息建構時，errorCode 物件保存，getMessage() 回傳自定義訊息
     */
    @Test
    @DisplayName("以 IErrorCode 與自定義訊息建構時，正確儲存錯誤碼物件並覆蓋 getMessage()")
    void whenConstructedWithIErrorCodeAndCustomMessage_storesCodeAndOverridesMessage() {
        String customMessage = "資料庫連線失敗";
        SystemException ex = new SystemException(CommonErrorCode.DATABASE_ERROR, customMessage);

        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.DATABASE_ERROR);
        assertThat(ex.getCode()).isEqualTo(CommonErrorCode.DATABASE_ERROR.getCode());
        assertThat(ex.getMessage()).isEqualTo(customMessage);
    }
}
