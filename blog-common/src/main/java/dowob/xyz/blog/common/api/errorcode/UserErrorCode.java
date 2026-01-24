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
  TOKEN_ACCESS_FORBIDDEN("A0105", "無權限訪問");

  /**
   * 錯誤碼
   */
  private final String code;

  /** 錯誤訊息 */
  private final String message;
}
