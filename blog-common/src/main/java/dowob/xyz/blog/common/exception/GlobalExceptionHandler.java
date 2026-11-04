package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
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

import java.util.Optional;
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

    /** 沿 cause 鏈尋找資料衝突例外的最大深度，防止異常的循環引用 */
    private static final int MAX_CAUSE_DEPTH = 16;

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
     * 處理資料完整性違反：只有唯一鍵衝突回 HTTP 409，其餘視為程式缺陷回 HTTP 500
     *
     * <p>
     * 這是兜底：service 層能明確辨識的衝突（如 slug 已被使用）應自行轉成更具體的業務錯誤碼，
     * 到這裡的都是未被攔下者。
     * </p>
     * <ul>
     *   <li>{@link DuplicateKeyException}（PostgreSQL 23505，直接或位於 cause 鏈中）：使用者可處理的衝突，
     *       回 409 {@link CommonErrorCode#DATA_CONFLICT}，以 WARN 記錄。</li>
     *   <li>其餘（NOT NULL、外鍵、CHECK、欄位超長等）：重試無效，多半是程式漏設欄位或驗證缺口，
     *       回 500 通用系統錯誤並以 <b>ERROR</b> 記錄完整例外，讓只看 ERROR 的監控抓得到。</li>
     * </ul>
     * <p>兩者回應都不含 SQL、約束名與欄位值；完整例外只進 log。</p>
     *
     * @param e       資料完整性違反例外
     * @param request 當前 HTTP 請求
     * @return HTTP 409（唯一鍵衝突）或 HTTP 500 回應
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException e, HttpServletRequest request) {
        return resolveDataConflict(e, request.getRequestURI())
                .orElseGet(() -> internalServerError(e, request.getRequestURI()));
    }

    /**
     * 處理樂觀鎖衝突（{@code @Version} 不符），回傳 HTTP 409 與「資料已被他人修改」錯誤碼
     *
     * @param e       樂觀鎖衝突例外
     * @param request 當前 HTTP 請求
     * @return HTTP 409 回應，body 含 {@link CommonErrorCode#OPTIMISTIC_LOCK_CONFLICT}
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailure(
            OptimisticLockingFailureException e, HttpServletRequest request) {
        return resolveDataConflict(e, request.getRequestURI())
                .orElseGet(() -> internalServerError(e, request.getRequestURI()));
    }

    /**
     * 處理其他未預期例外，回傳 HTTP 500 與通用錯誤訊息
     *
     * <p>
     * Spring Data JDBC 的 {@code save} 會把約束違反包在一般 {@code RuntimeException}
     * （{@code DbActionExecutionException}）內，型別比對只會落到這裡；因此先沿 cause 鏈尋找
     * 唯一鍵衝突或樂觀鎖失敗，找到就與直接拋出時回相同的 409（log 帶真實 URI 並記外層例外，內層作為 cause）。
     * 其餘（含非唯一鍵的完整性違反）以 ERROR 級別記錄完整 stack trace，並回傳 HTTP 500。
     * </p>
     *
     * @param e       未預期例外
     * @param request 當前 HTTP 請求
     * @return HTTP 409（cause 鏈含唯一鍵衝突／樂觀鎖失敗）或 HTTP 500 回應
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e, HttpServletRequest request) {
        return resolveDataConflict(e, request.getRequestURI())
                .orElseGet(() -> internalServerError(e, request.getRequestURI()));
    }

    /**
     * 沿例外本身與 cause 鏈尋找「使用者可處理的資料衝突」：樂觀鎖失敗或唯一鍵衝突。
     *
     * @param e   最外層例外（log 記這個，內層自然作為 cause 帶出）
     * @param uri 請求 URI
     * @return 找到時為 409 回應（已記 WARN）；找不到為 empty
     */
    private Optional<ResponseEntity<ApiResponse<Void>>> resolveDataConflict(Throwable e, String uri) {
        Throwable cause = e;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof OptimisticLockingFailureException) {
                log.warn("Optimistic Locking Failure at {}: {}", uri, e.toString(), e);
                return Optional.of(ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.failed(CommonErrorCode.OPTIMISTIC_LOCK_CONFLICT)));
            }
            if (cause instanceof DuplicateKeyException) {
                log.warn("Duplicate Key at {}: {}", uri, e.toString(), e);
                return Optional.of(ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.failed(CommonErrorCode.DATA_CONFLICT)));
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return Optional.empty();
    }

    /**
     * 組出通用 500 回應，完整例外以 ERROR 記錄。
     */
    private ResponseEntity<ApiResponse<Void>> internalServerError(Throwable e, String uri) {
        log.error("Unhandled Exception at {}", uri, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failed("500", "系統內部錯誤"));
    }
}
