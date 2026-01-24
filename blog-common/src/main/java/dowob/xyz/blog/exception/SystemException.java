package dowob.xyz.blog.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;

/**
 * 系統異常 表示系統內部執行錯誤，非用戶預期行為 (B類/C類錯誤碼) 日誌級別建議: ERROR
 *
 * @author Yuan
 * @version 1.0
 */
public class SystemException extends BaseException {

    /**
     * 構造方法
     *
     * @param errorCode 錯誤碼接口
     */
    public SystemException(IErrorCode errorCode) {
        super(errorCode);
    }


    /**
     * 構造方法
     *
     * @param errorCode 錯誤碼接口
     * @param message   自定義訊息
     */
    public SystemException(IErrorCode errorCode, String message) {
        super(errorCode, message);
  }
}
