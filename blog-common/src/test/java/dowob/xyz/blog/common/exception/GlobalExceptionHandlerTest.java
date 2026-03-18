package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GlobalExceptionHandler 單元測試
 *
 * <p>
 * 直接實例化 {@link GlobalExceptionHandler} 並呼叫各 handler 方法，
 * 驗證各類例外對應正確的 HTTP 狀態碼與回應結構。
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
     * 驗證 BusinessException 由 handleBusinessException 處理，回傳 HTTP 400 及正確錯誤碼
     */
    @Test
    @DisplayName("BusinessException 應回傳 HTTP 400 並包含錯誤碼")
    void whenBusinessException_returns400WithErrorCode() {
        BusinessException ex = new BusinessException(CommonErrorCode.REQUEST_PARAM_MISSING);

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(CommonErrorCode.REQUEST_PARAM_MISSING.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo(CommonErrorCode.REQUEST_PARAM_MISSING.getMessage());
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

    /**
     * 驗證 MethodArgumentNotValidException 由 handleValidationException 處理，
     * 回傳 HTTP 400 及欄位錯誤訊息
     */
    @Test
    @DisplayName("MethodArgumentNotValidException 應回傳 HTTP 400 並包含欄位錯誤")
    void whenValidationException_returns400WithFieldErrors() throws NoSuchMethodException {
        Method dummyMethod = String.class.getMethod("charAt", int.class);
        MethodParameter methodParameter = new MethodParameter(dummyMethod, 0);
        MapBindingResult bindingResult = new MapBindingResult(new HashMap<>(), "target");
        bindingResult.rejectValue("title", "NotBlank", "不能為空");
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
        assertThat(response.getBody().getMessage()).contains("title");
    }

    /**
     * 驗證 BindException 由 handleBindException 處理，
     * 回傳 HTTP 400 及欄位錯誤訊息
     */
    @Test
    @DisplayName("BindException 應回傳 HTTP 400 並包含欄位錯誤")
    void whenBindException_returns400WithFieldErrors() {
        MapBindingResult bindingResult = new MapBindingResult(new HashMap<>(), "target");
        bindingResult.rejectValue("slug", "NotBlank", "不能為空");
        BindException ex = new BindException(bindingResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleBindException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
        assertThat(response.getBody().getMessage()).contains("slug");
    }

    /**
     * 驗證未處理例外由 handleException 捕獲，回傳 HTTP 500 及系統錯誤訊息
     */
    @Test
    @DisplayName("未處理 Exception 應回傳 HTTP 500 且訊息不包含例外細節")
    void whenUnhandledException_returns500WithGenericMessage() {
        Exception ex = new RuntimeException("Unexpected error");

        ResponseEntity<ApiResponse<Void>> response = handler.handleException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("500");
        assertThat(response.getBody().getMessage()).isEqualTo("系統內部錯誤");
        assertThat(response.getBody().getMessage()).doesNotContain("Unexpected error");
    }

    /**
     * 驗證 handleAccessDeniedException 原樣重新拋出 AccessDeniedException，
     * 讓 Spring Security ExceptionTranslationFilter 處理並回傳 HTTP 403
     */
    @Test
    @DisplayName("AccessDeniedException 應原樣重新拋出，不被 handler 吞掉")
    void whenAccessDeniedException_rethrowsOriginalException() {
        AccessDeniedException ex = new AccessDeniedException("Access is denied");

        assertThatThrownBy(() -> handler.handleAccessDeniedException(ex))
                .isInstanceOf(AccessDeniedException.class)
                .isSameAs(ex)
                .hasMessage("Access is denied");
    }

    /**
     * 驗證 handleResponseStatusException 保留原始 HTTP 404 狀態碼並回傳 reason
     */
    @Test
    @DisplayName("ResponseStatusException(404) 應回傳 HTTP 404 並以 reason 作為 message")
    void whenResponseStatusException404_returns404WithReason() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found");

        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("404");
        assertThat(response.getBody().getMessage()).isEqualTo("Article not found");
    }

    /**
     * 驗證 handleResponseStatusException 保留原始 HTTP 400 狀態碼並回傳 reason
     */
    @Test
    @DisplayName("ResponseStatusException(400) 應回傳 HTTP 400 並以 reason 作為 message")
    void whenResponseStatusException400_returns400WithReason() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request parameter");

        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid request parameter");
    }

    /**
     * 驗證 handleResponseStatusException 當 reason 為 null 時，code 仍正確，message 為 null
     */
    @Test
    @DisplayName("ResponseStatusException reason 為 null 時，code 應正確且 message 為 null")
    void whenResponseStatusExceptionWithNullReason_returnsCorrectCodeAndNullMessage() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatusCode.valueOf(422));

        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("422");
        assertThat(response.getBody().getMessage()).isNull();
    }

    /**
     * 驗證 MissingServletRequestParameterException 由 handleBadRequestException 處理，回傳 HTTP 400
     */
    @Test
    @DisplayName("MissingServletRequestParameterException 應回傳 HTTP 400")
    void handleMissingServletRequestParam_returns400() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("token", "String");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequestException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
    }

    /**
     * 驗證 MethodArgumentTypeMismatchException 由 handleBadRequestException 處理，回傳 HTTP 400
     */
    @Test
    @DisplayName("MethodArgumentTypeMismatchException 應回傳 HTTP 400")
    void handleMethodArgumentTypeMismatch_returns400() throws NoSuchMethodException {
        Method dummyMethod = String.class.getMethod("charAt", int.class);
        MethodParameter methodParameter = new MethodParameter(dummyMethod, 0);
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", methodParameter, null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequestException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
    }

    /**
     * 驗證 HttpMessageNotReadableException 由 handleBadRequestException 處理，回傳 HTTP 400
     */
    @Test
    @DisplayName("HttpMessageNotReadableException 應回傳 HTTP 400")
    void handleHttpMessageNotReadable_returns400() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("Malformed JSON", new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequestException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
    }

    /**
     * 驗證 HttpRequestMethodNotSupportedException 由 handleMethodNotAllowedException 處理，回傳 HTTP 405
     */
    @Test
    @DisplayName("HttpRequestMethodNotSupportedException 應回傳 HTTP 405")
    void handleHttpRequestMethodNotSupported_returns405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("DELETE");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotAllowedException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("405");
    }

    /**
     * 驗證 ConstraintViolationException 由 handleBadRequestException 處理，回傳 HTTP 400
     */
    @Test
    @DisplayName("ConstraintViolationException 應回傳 HTTP 400")
    void handleConstraintViolation_returns400() {
        ConstraintViolationException ex =
                new ConstraintViolationException("must not be blank", Set.of());

        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequestException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
    }
}
