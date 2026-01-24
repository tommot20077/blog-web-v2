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
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 處理業務異常 (主動拋出的預期錯誤) HTTP Status: 200 OK 理由：業務邏輯的否定（如密碼錯誤）屬於正常的業務流程處理結果
     *
     * @param e 業務異常對象
     *
     * @return 統一響應對象
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusinessException(BusinessException e) {
        log.info("業務異常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());

        return ResponseEntity.ok(ApiResponse.failed(e.getErrorCode()));
    }


    /**
     * 處理系統異常 (已知的系統級錯誤) HTTP Status: 500 Internal Server Error 理由：伺服器內部發生錯誤，需要監控系統捕獲
     *
     * @param e 系統異常對象
     *
     * @return 統一響應對象
     */
    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiResponse<?>> handleSystemException(SystemException e) {
        log.error("系統內部異常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failed(e.getErrorCode()));
    }


    /**
     * 處理參數校驗異常 (@Valid 失敗) HTTP Status: 400 Bad Request
     *
     * @param e 參數校驗異常對象
     *
     * @return 統一響應對象
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
     * 處理請求方法不支援異常 HTTP Status: 405 Method Not Allowed
     *
     * @param e 請求方法不支援異常對象
     *
     * @return 統一響應對象
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleMethodNotSupportedException(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.info("請求方法不支援: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.failed(CommonErrorCode.REQUEST_METHOD_NOT_SUPPORTED));
    }


    /**
     * 處理請求路徑不存在異常 HTTP Status: 404 Not Found
     *
     * @param e 請求路徑不存在異常對象
     *
     * @return 統一響應對象
     */
    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNoHandlerFoundException(org.springframework.web.servlet.NoHandlerFoundException e) {
        log.info("請求路徑不存在: {}", e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failed(CommonErrorCode.REQUEST_PATH_NOT_FOUND));
    }


    /**
     * 處理所有未捕獲的異常 HTTP Status: 500 Internal Server Error
     *
     * @param e 未知異常對象
     *
     * @return 統一響應對象
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
        log.error("系統未知異常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failed(CommonErrorCode.SYSTEM_EXECUTION_ERROR));
  }
}
