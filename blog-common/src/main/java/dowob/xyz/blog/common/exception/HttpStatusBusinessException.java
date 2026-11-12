package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 可指定 HTTP 狀態碼的業務例外。
 */
@Getter
public class HttpStatusBusinessException extends BusinessException {

    private final HttpStatus status;

    public HttpStatusBusinessException(IErrorCode errorCode, HttpStatus status) {
        super(errorCode);
        this.status = status;
    }

    public HttpStatusBusinessException(IErrorCode errorCode, String message, HttpStatus status) {
        super(errorCode, message);
        this.status = status;
    }
}
