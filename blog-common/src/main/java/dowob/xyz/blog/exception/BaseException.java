package dowob.xyz.blog.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.Getter;

/**
 * 部落格專案頂層異常基類 所有自定義異常都應繼承此類
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
public abstract class BaseException extends RuntimeException {
    /**
     * 錯誤碼接口
     */
    private final IErrorCode errorCode;


    /**
     * 構造方法 (基於錯誤碼)
     *
     * @param errorCode 錯誤碼接口
     */
    public BaseException(IErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }


    /**
     * 構造方法 (基於錯誤碼與自定義訊息)
     *
     * @param errorCode 錯誤碼接口
     * @param message   自定義訊息
     */
    public BaseException(IErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
  }
}
