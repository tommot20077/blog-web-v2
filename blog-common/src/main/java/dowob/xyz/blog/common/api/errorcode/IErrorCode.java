package dowob.xyz.blog.common.api.errorcode;

/**
 * 錯誤碼介面
 * 方便後續擴展不同的錯誤碼枚舉
 */
public interface IErrorCode {
    String getCode();
    String getMessage();
}
