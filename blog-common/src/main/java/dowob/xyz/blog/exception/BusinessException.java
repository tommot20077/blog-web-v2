package dowob.xyz.blog.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;

/**
 * 業務異常
 * 表示用戶操作錯誤或業務規則攔截 (A類錯誤碼)
 * 日誌級別: INFO
 */
public class BusinessException extends BaseException {

    public BusinessException(IErrorCode errorCode) {
        super(errorCode);
    }

    public BusinessException(IErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}