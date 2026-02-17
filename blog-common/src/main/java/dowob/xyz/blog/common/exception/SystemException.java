package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;

/**
 * 系統例外
 *
 * <p>
 * 表示系統內部執行錯誤，屬非使用者預期行為（B 類 / C 類錯誤碼）。
 * 日誌級別建議：ERROR。
 * 繼承 {@link BaseException}，可透過 {@link #getErrorCode()} 取得完整錯誤碼物件。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public class SystemException extends BaseException {

    /**
     * 以錯誤碼建構，訊息由 {@link IErrorCode#getMessage()} 提供
     *
     * @param errorCode 錯誤碼物件
     */
    public SystemException(IErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 以錯誤碼與自訂訊息建構，允許覆蓋預設訊息
     *
     * @param errorCode 錯誤碼物件
     * @param message   自訂例外訊息
     */
    public SystemException(IErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
