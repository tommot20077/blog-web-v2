package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * 全局異常處理
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 處理業務異常，回傳 HTTP 400 與結構化錯誤碼
     *
     * <p>
     * {@link BusinessException} 代表用戶端操作引發的業務規則違反，
     * 以 WARN 級別記錄，並回傳 HTTP 400。
     * </p>
     *
     * @param e       業務例外
     * @param request 當前 HTTP 請求
     * @return HTTP 400 回應，body 含錯誤碼與訊息
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("Business Exception: {} at {}", e.getMessage(), request.getRequestURI());
        HttpStatus status = e instanceof HttpStatusBusinessException statusException
                ? statusException.getStatus()
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(ApiResponse.failed(e.getCode(), e.getMessage()));
    }

    /**
     * 處理系統例外，回傳 HTTP 500 與結構化錯誤碼
     *
     * <p>
     * {@link SystemException} 代表 B 類 / C 類系統內部錯誤，
     * 以 ERROR 級別記錄完整 stack trace，並回傳 HTTP 500。
     * </p>
     *
     * @param e       系統例外
     * @param request 當前 HTTP 請求
     * @return HTTP 500 回應，body 含錯誤碼與訊息
     */
    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiResponse<Void>> handleSystemException(SystemException e, HttpServletRequest request) {
        log.error("System Exception: {} at {}", e.getMessage(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failed(e.getCode(), e.getMessage()));
    }

    /**
     * 處理參數校驗異常 (JSON Body)，回傳 HTTP 400 與欄位錯誤訊息
     *
     * @param e 參數校驗例外
     * @return HTTP 400 回應，body 含欄位錯誤描述
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation Exception: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed("400", message));
    }

    /**
     * 處理參數校驗異常 (Form Data)，回傳 HTTP 400 與欄位錯誤訊息
     *
     * @param e 綁定例外
     * @return HTTP 400 回應，body 含欄位錯誤描述
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Bind Exception: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed("400", message));
    }

    /**
     * 處理存取拒絕異常（@PreAuthorize 驗證失敗）
     *
     * <p>
     * 必須重新拋出，讓 Spring Security 的 {@code ExceptionTranslationFilter}
     * 統一處理並回傳 HTTP 403，避免被 catch-all handler 吞掉而回傳 HTTP 200。
     * </p>
     *
     * @param e 存取拒絕異常
     * @throws AccessDeniedException 原樣重新拋出
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDeniedException(AccessDeniedException e) throws AccessDeniedException {
        throw e;
    }

    /**
     * 處理 ResponseStatusException，保留原始 HTTP 狀態碼
     *
     * <p>
     * {@link ResponseStatusException} 攜帶明確的 HTTP 狀態（如 404、400），
     * 必須在 catch-all {@code Exception} handler 之前處理，
     * 以確保正確的 HTTP 狀態碼被回傳給客戶端。
     * </p>
     *
     * @param e ResponseStatusException 實例
     * @return 包含錯誤訊息的回應，HTTP 狀態碼由例外決定
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException e) {
        log.warn("ResponseStatusException: {} {}", e.getStatusCode(), e.getReason());
        if (e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.failed(CommonErrorCode.UNAUTHENTICATED));
        }
        if (e.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.failed(CommonErrorCode.FORBIDDEN));
        }
        return ResponseEntity
                .status(e.getStatusCode())
                .body(ApiResponse.failed(String.valueOf(e.getStatusCode().value()), e.getReason()));
    }

    /**
     * 處理缺少必填參數，回傳 HTTP 400 與參數名稱
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("Missing Parameter: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed("400", "缺少必要參數: " + e.getParameterName()));
    }

    /**
     * 處理參數型別轉換失敗，回傳 HTTP 400 與通用安全訊息
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Type Mismatch: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed("400", "參數類型錯誤"));
    }

    /**
     * 處理 JSON 格式錯誤，回傳 HTTP 400 與通用安全訊息
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Message Not Readable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed("400", "請求格式錯誤"));
    }

    /**
     * 處理缺少必填 Header，回傳 HTTP 400 與 Header 名稱
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException e) {
        log.warn("Missing Header: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed("400", "缺少必要標頭: " + e.getHeaderName()));
    }

    /**
     * 處理 Bean Validation 失敗，回傳 HTTP 400 與驗證訊息
     *
     * <p>violation messages 是我們自訂的驗證訊息（如 {@code @Size}、{@code @NotBlank}），可安全回傳。</p>
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("Constraint Violation: {}", e.getMessage());
        String message;
        if (e.getConstraintViolations() != null && !e.getConstraintViolations().isEmpty()) {
            message = e.getConstraintViolations().stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining(", "));
        } else {
            message = "參數驗證失敗";
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed("400", message));
    }

    /**
     * 處理不支援的 HTTP Method，回傳 HTTP 405
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowedException(HttpRequestMethodNotSupportedException e) {
        log.warn("Method Not Allowed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.failed("405", e.getMessage()));
    }

    /**
     * 處理不支援的 Content-Type，回傳 HTTP 415
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaTypeException(HttpMediaTypeNotSupportedException e) {
        log.warn("Unsupported Media Type: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.failed("415", e.getMessage()));
    }

    /**
     * 處理其他未預期例外，回傳 HTTP 500 與通用錯誤訊息
     *
     * <p>
     * 以 ERROR 級別記錄完整 stack trace，並回傳 HTTP 500。
     * </p>
     *
     * @param e 未預期例外
     * @return HTTP 500 回應，body 含系統錯誤訊息
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled Exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failed("500", "系統內部錯誤"));
    }
}
