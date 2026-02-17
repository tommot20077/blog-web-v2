package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BusinessException 單元測試
 *
 * <p>
 * 驗證 {@link BusinessException} 正確繼承 {@link BaseException}，
 * 並完整保存 {@link dowob.xyz.blog.common.api.errorcode.IErrorCode} 物件。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("BusinessException 單元測試")
class BusinessExceptionTest {

    /**
     * 驗證以 IErrorCode 建構時，errorCode 物件正確保存，getCode() 委派給 errorCode
     */
    @Test
    @DisplayName("以 IErrorCode 建構時，正確儲存錯誤碼物件並提供 getCode()")
    void whenConstructedWithIErrorCode_storesCodeAndErrorCode() {
        BusinessException ex = new BusinessException(CommonErrorCode.REQUEST_PARAM_MISSING);

        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.REQUEST_PARAM_MISSING);
        assertThat(ex.getCode()).isEqualTo(CommonErrorCode.REQUEST_PARAM_MISSING.getCode());
        assertThat(ex.getMessage()).isEqualTo(CommonErrorCode.REQUEST_PARAM_MISSING.getMessage());
    }

    /**
     * 驗證以 IErrorCode + 自定義訊息建構時，errorCode 物件保存，getMessage() 回傳自定義訊息
     */
    @Test
    @DisplayName("以 IErrorCode 與自定義訊息建構時，正確儲存錯誤碼物件並覆蓋 getMessage()")
    void whenConstructedWithIErrorCodeAndCustomMessage_storesCodeAndOverridesMessage() {
        String customMessage = "自定義錯誤訊息";
        BusinessException ex = new BusinessException(CommonErrorCode.REQUEST_PARAM_MISSING, customMessage);

        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.REQUEST_PARAM_MISSING);
        assertThat(ex.getCode()).isEqualTo(CommonErrorCode.REQUEST_PARAM_MISSING.getCode());
        assertThat(ex.getMessage()).isEqualTo(customMessage);
    }
}
