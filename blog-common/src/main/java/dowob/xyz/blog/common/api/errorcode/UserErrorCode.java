package dowob.xyz.blog.common.api.errorcode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用戶/認證模組錯誤碼 (User Module) 範圍：A01
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum UserErrorCode implements IErrorCode {

    /**
     * 用戶不存在
     */
    USER_NOT_FOUND("A0101", "用戶不存在"),

    /**
     * 帳號或密碼錯誤
     */
    USER_PASSWORD_ERROR("A0102", "帳號或密碼錯誤"),

    /**
     * 用戶已存在
     */
    USER_HAS_EXISTED("A0103", "用戶已存在"),

    /**
     * Token無效或已過期
     */
    TOKEN_INVALID("A0104", "Token無效或已過期"),

    /**
     * 無權限訪問
     */
    TOKEN_ACCESS_FORBIDDEN("A0105", "無權限訪問"),

    /**
     * 信箱已被註冊
     */
    EMAIL_DUPLICATED("A0106", "該信箱已被註冊"),

    /**
     * 暱稱已被使用
     */
    NICKNAME_DUPLICATED("A0107", "該暱稱已被使用"),

    /**
     * 帳號已被停權
     */
    ACCOUNT_SUSPENDED("A0108", "帳號已被停權或刪除"),

    /**
     * 無效的用戶狀態
     */
    INVALID_USER_STATUS("A0109", "無效的用戶狀態"),

    /**
     * 無效的用戶角色
     */
    INVALID_USER_ROLE("A0110", "無效的用戶角色"),

    /**
     * 電子信箱尚未驗證
     */
    EMAIL_NOT_VERIFIED("A0111", "請先驗證電子信箱"),

    /**
     * 用戶名已被使用
     */
    USERNAME_DUPLICATED("A0112", "該用戶名已被使用"),

    /**
     * 請求頻率超過限制
     */
    RATE_LIMIT_EXCEEDED("A0113", "請求過於頻繁，請稍後再試"),

    /**
     * 帳號因多次失敗嘗試被暫時鎖定
     */
    ACCOUNT_LOCKED("A0114", "帳號已因多次失敗嘗試被暫時鎖定，請15分鐘後再試")

    ;

    /**
     * 錯誤碼
     */
    private final String code;

    /**
     * 錯誤訊息
     */
    private final String message;
}
