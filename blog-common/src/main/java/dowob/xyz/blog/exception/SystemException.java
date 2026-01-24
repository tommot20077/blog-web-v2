package dowob.xyz.blog.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;

/**
 * 系統異常
 * 表示系統內部執行錯誤，非用戶預期行為 (B類/C類錯誤碼)
 * 日誌級別建議: ERROR
 */
public class SystemException extends BaseException {

    public SystemException(IErrorCode errorCode) {
        super(errorCode);
    }

    public SystemException(IErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
