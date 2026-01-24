package dowob.xyz.blog.infrastructure.exception;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.exception.BusinessException;
import dowob.xyz.blog.exception.SystemException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全域異常攔截器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 處理業務異常 (主動拋出的預期錯誤)
     * HTTP Status: 200 OK
     * 理由：業務邏輯的否定（如密碼錯誤）屬於正常的業務流程處理結果
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusinessException(BusinessException e) {
        log.info("業務異常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());

        return ResponseEntity.ok(ApiResponse.failed(e.getErrorCode()));
    }

    /**
     * 處理系統異常 (已知的系統級錯誤)
     * HTTP Status: 500 Internal Server Error
     * 理由：伺服器內部發生錯誤，需要監控系統捕獲
     */
    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiResponse<?>> handleSystemException(SystemException e) {
        log.error("系統內部異常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failed(e.getErrorCode()));
    }

    /**
     * 處理參數校驗異常 (@Valid 失敗)
     * HTTP Status: 400 Bad Request
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        String message = null;
        if (bindingResult.hasErrors()) {
            FieldError fieldError = bindingResult.getFieldError();
            if (fieldError != null) {
                message = fieldError.getField() + " " + fieldError.getDefaultMessage();
            }
        }
        log.info("參數校驗失敗: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failed(CommonErrorCode.PARAM_VALID_ERROR.getCode(), message));
    }

    /**
     * 處理所有未捕獲的異常 (兜底)
     * HTTP Status: 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
        log.error("系統未知異常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failed(CommonErrorCode.SYSTEM_EXECUTION_ERROR));
    }
}
