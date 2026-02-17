package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 單元測試
 *
 * <p>
 * 直接實例化 {@link GlobalExceptionHandler} 並呼叫各 handler 方法，
 * 驗證業務異常（HTTP 200）與系統異常（HTTP 500）的回應結構正確。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("GlobalExceptionHandler 單元測試")
class GlobalExceptionHandlerTest {

    /** 受測目標 */
    private GlobalExceptionHandler handler;

    /** 模擬 HTTP 請求 */
    private MockHttpServletRequest request;

    /**
     * 每個測試前初始化 handler 與 request
     */
    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");
    }

    /**
     * 驗證 BusinessException 由 handleBusinessException 處理，回傳 HTTP 200 及正確錯誤碼
     */
    @Test
    @DisplayName("BusinessException 應回傳 HTTP 200 並包含錯誤碼")
    void whenBusinessException_returns200WithErrorCode() {
        BusinessException ex = new BusinessException(CommonErrorCode.REQUEST_PARAM_MISSING);

        ApiResponse<Void> response = handler.handleBusinessException(ex, request);

        assertThat(response.getCode()).isEqualTo(CommonErrorCode.REQUEST_PARAM_MISSING.getCode());
        assertThat(response.getMessage()).isEqualTo(CommonErrorCode.REQUEST_PARAM_MISSING.getMessage());
    }

    /**
     * 驗證 SystemException 由 handleSystemException 處理，回傳 HTTP 500 及正確錯誤碼
     */
    @Test
    @DisplayName("SystemException 應回傳 HTTP 500 並包含錯誤碼")
    void whenSystemException_returns500WithErrorCode() {
        SystemException ex = new SystemException(CommonErrorCode.SYSTEM_EXECUTION_ERROR);

        ResponseEntity<ApiResponse<Void>> response = handler.handleSystemException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(CommonErrorCode.SYSTEM_EXECUTION_ERROR.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo(CommonErrorCode.SYSTEM_EXECUTION_ERROR.getMessage());
    }
}
