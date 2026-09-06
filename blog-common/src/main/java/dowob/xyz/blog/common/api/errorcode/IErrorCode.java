package dowob.xyz.blog.common.api.errorcode;

/**
 * 錯誤碼介面 方便後續擴展不同的錯誤碼枚舉
 *
 * @author Yuan
 * @version 1.0
 */
public interface IErrorCode {
    /**
     * 獲取錯誤碼
     *
     * @return 錯誤碼字串
     */
    String getCode();

    /**
     * 獲取錯誤訊息
     *
     * @return 錯誤訊息字串
     */
    String getMessage();
}
