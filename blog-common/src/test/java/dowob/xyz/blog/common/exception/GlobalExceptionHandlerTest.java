package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import jakarta.validation.ConstraintViolation;
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
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
     * 驗證 MissingServletRequestParameterException 回傳安全訊息，包含參數名但不洩漏內部細節
     */
    @Test
    @DisplayName("MissingServletRequestParameterException 應回傳 '缺少必要參數: {name}'，不洩漏內部細節")
    void handleMissingParam_returnsSanitizedMessage() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("token", "String");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMissingParameter(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
        assertThat(response.getBody().getMessage()).isEqualTo("缺少必要參數: token");
    }

    /**
     * 驗證 MethodArgumentTypeMismatchException 回傳通用安全訊息，不洩漏 Java 型別資訊
     */
    @Test
    @DisplayName("MethodArgumentTypeMismatchException 應回傳 '參數類型錯誤'，不洩漏 Java 型別資訊")
    void handleTypeMismatch_returnsSanitizedMessage() throws NoSuchMethodException {
        Method dummyMethod = String.class.getMethod("charAt", int.class);
        MethodParameter methodParameter = new MethodParameter(dummyMethod, 0);
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", methodParameter, null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
        assertThat(response.getBody().getMessage()).isEqualTo("參數類型錯誤");
        assertThat(response.getBody().getMessage()).doesNotContain("Long", "java.lang");
    }

    /**
     * 驗證 HttpMessageNotReadableException 回傳通用安全訊息，不洩漏 Jackson 反序列化細節
     */
    @Test
    @DisplayName("HttpMessageNotReadableException 應回傳 '請求格式錯誤'，不洩漏 Jackson 反序列化細節")
    void handleMessageNotReadable_returnsSanitizedMessage() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException(
                        "JSON parse error: Cannot deserialize value of type `java.time.LocalDateTime`",
                        new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiResponse<Void>> response = handler.handleMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
        assertThat(response.getBody().getMessage()).isEqualTo("請求格式錯誤");
        assertThat(response.getBody().getMessage()).doesNotContain("java.time", "Cannot deserialize");
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
     * 驗證 MissingRequestHeaderException 回傳安全訊息，包含 header 名稱
     */
    @Test
    @DisplayName("MissingRequestHeaderException 應回傳 '缺少必要標頭: {headerName}'")
    void handleMissingHeader_returnsSanitizedMessage() throws NoSuchMethodException {
        Method dummyMethod = String.class.getMethod("charAt", int.class);
        MethodParameter methodParameter = new MethodParameter(dummyMethod, 0);
        MissingRequestHeaderException ex =
                new MissingRequestHeaderException("X-Request-Id", methodParameter);

        ResponseEntity<ApiResponse<Void>> response = handler.handleMissingHeader(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
        assertThat(response.getBody().getMessage()).isEqualTo("缺少必要標頭: X-Request-Id");
    }

    /**
     * 驗證 ConstraintViolationException 有 violations 時，回傳 violation messages（我們自訂的驗證訊息，安全）
     */
    @Test
    @DisplayName("ConstraintViolationException 有 violations 時，應回傳 violation messages")
    @SuppressWarnings("unchecked")
    void handleConstraintViolation_withViolations_returnsViolationMessages() {
        ConstraintViolation<Object> violation1 = mock(ConstraintViolation.class);
        when(violation1.getMessage()).thenReturn("不能為空");
        ConstraintViolation<Object> violation2 = mock(ConstraintViolation.class);
        when(violation2.getMessage()).thenReturn("長度必須在 1 到 100 之間");

        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation1, violation2));

        ResponseEntity<ApiResponse<Void>> response = handler.handleConstraintViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
        assertThat(response.getBody().getMessage()).contains("不能為空");
        assertThat(response.getBody().getMessage()).contains("長度必須在 1 到 100 之間");
    }

    /**
     * 驗證 ConstraintViolationException 無 violations 時，回傳通用訊息，不洩漏內部細節
     */
    @Test
    @DisplayName("ConstraintViolationException 無 violations 時，應回傳 '參數驗證失敗'")
    void handleConstraintViolation_withoutViolations_returnsGenericMessage() {
        ConstraintViolationException ex = new ConstraintViolationException("internal detail", Set.of());

        ResponseEntity<ApiResponse<Void>> response = handler.handleConstraintViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
        assertThat(response.getBody().getMessage()).isEqualTo("參數驗證失敗");
        assertThat(response.getBody().getMessage()).doesNotContain("internal detail");
    }

    /**
     * 驗證 ConstraintViolationException 的 violations 為 null 時，回傳通用訊息
     */
    @Test
    @DisplayName("ConstraintViolationException violations 為 null 時，應回傳 '參數驗證失敗'")
    void handleConstraintViolation_withNullViolations_returnsGenericMessage() {
        ConstraintViolationException ex = new ConstraintViolationException("internal detail", null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleConstraintViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("400");
        assertThat(response.getBody().getMessage()).isEqualTo("參數驗證失敗");
    }

    /**
     * 驗證 HttpMediaTypeNotSupportedException 回傳 HTTP 415
     */
    @Test
    @DisplayName("HttpMediaTypeNotSupportedException 應回傳 HTTP 415")
    void handleUnsupportedMediaType_returns415() {
        HttpMediaTypeNotSupportedException ex =
                new HttpMediaTypeNotSupportedException("Content-Type 'text/plain' is not supported");

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnsupportedMediaTypeException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("415");
    }
}
