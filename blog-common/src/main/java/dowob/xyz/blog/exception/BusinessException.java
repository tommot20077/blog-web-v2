package dowob.xyz.blog.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;

/**
 * 業務異常 表示用戶操作錯誤或業務規則攔截 (A類錯誤碼) 日誌級別: INFO
 *
 * @author Yuan
 * @version 1.0
 */
public class BusinessException extends BaseException {

    /**
     * 構造方法
     *
     * @param errorCode 錯誤碼接口
     */
    public BusinessException(IErrorCode errorCode) {
        super(errorCode);
    }


    /**
     * 構造方法
     *
     * @param errorCode 錯誤碼接口
     * @param message   自定義訊息
     */
    public BusinessException(IErrorCode errorCode, String message) {
        super(errorCode, message);
  }
}
