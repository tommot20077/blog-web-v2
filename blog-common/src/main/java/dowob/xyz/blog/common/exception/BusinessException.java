package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;

/**
 * 業務例外
 *
 * <p>
 * 表示使用者操作錯誤或業務規則攔截（A 類錯誤碼）。
 * 日誌級別建議：INFO。
 * 繼承 {@link BaseException}，可透過 {@link #getErrorCode()} 取得完整錯誤碼物件。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public class BusinessException extends BaseException {

    /**
     * 以錯誤碼建構，訊息由 {@link IErrorCode#getMessage()} 提供
     *
     * @param errorCode 錯誤碼物件
     */
    public BusinessException(IErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 以錯誤碼與自訂訊息建構，允許覆蓋預設訊息
     *
     * @param errorCode 錯誤碼物件
     * @param message   自訂例外訊息
     */
    public BusinessException(IErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
