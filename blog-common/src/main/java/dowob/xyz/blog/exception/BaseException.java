package dowob.xyz.blog.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.Getter;

/**
 * 部落格專案頂層異常基類
 * 所有自定義異常都應繼承此類
 */
@Getter
public abstract class BaseException extends RuntimeException {
    private final IErrorCode errorCode;

    public BaseException(IErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BaseException(IErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
