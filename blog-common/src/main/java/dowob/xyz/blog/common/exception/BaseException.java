package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.Getter;

/**
 * 部落格專案頂層例外基礎類別
 *
 * <p>
 * 所有自訂例外皆應繼承此類別。
 * 持有型別安全的 {@link IErrorCode} 物件，使攔截器層
 * 可直接取得 enum 常數，避免純字串碼帶來的型別損失。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
public abstract class BaseException extends RuntimeException {

    /**
     * 錯誤碼物件，持有結構化的錯誤碼與訊息
     */
    private final IErrorCode errorCode;

    /**
     * 以錯誤碼建構，訊息由 {@link IErrorCode#getMessage()} 提供
     *
     * @param errorCode 錯誤碼物件，不可為 null
     */
    public BaseException(IErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 以錯誤碼與自訂訊息建構，允許覆蓋預設訊息
     *
     * @param errorCode 錯誤碼物件，不可為 null
     * @param message   自訂例外訊息
     */
    public BaseException(IErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 取得錯誤碼字串，委派給 {@link IErrorCode#getCode()}
     *
     * @return 錯誤碼字串
     */
    public String getCode() {
        return errorCode.getCode();
    }
}
