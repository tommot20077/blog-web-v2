package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.Getter;

/**
 * 業務異常
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(IErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }
}
