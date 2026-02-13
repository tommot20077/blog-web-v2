package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
     * 處理業務異常
     */
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("Business Exception: {} at {}", e.getMessage(), request.getRequestURI());
        return ApiResponse.failed(e.getCode(), e.getMessage());
    }

    /**
     * 處理參數校驗異常 (JSON Body)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation Exception: {}", message);
        return ApiResponse.failed("400", message);
    }

    /**
     * 處理參數校驗異常 (Form Data)
     */
    @ExceptionHandler(BindException.class)
    public ApiResponse<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Bind Exception: {}", message);
        return ApiResponse.failed("400", message);
    }

    /**
     * 處理其他異常
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("Unhandled Exception", e);
        return ApiResponse.failed("500", "系統內部錯誤: " + e.getMessage());
    }
}
