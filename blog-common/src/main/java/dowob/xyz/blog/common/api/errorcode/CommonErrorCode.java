package dowob.xyz.blog.common.api.errorcode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用/系統錯誤碼 (Common & System) 包含：成功(00000)、通用參數錯誤(A00)、系統錯誤(B00)
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum CommonErrorCode implements IErrorCode {

    /**
     * 成功
     */
    SUCCESS("00000", "操作成功"),

    /**
     * 參數校驗失敗
     */
    PARAM_VALID_ERROR("A0001", "參數校驗失敗"),

    /**
     * 不支持的請求方法
     */
    REQUEST_METHOD_NOT_SUPPORTED("A0002", "不支持的請求方法"),

    /**
     * 請求路徑不存在
     */
    REQUEST_PATH_NOT_FOUND("A0003", "請求路徑不存在"),

    /**
     * 系統執行出錯，請稍後再試
     */
    SYSTEM_EXECUTION_ERROR("B0001", "系統執行出錯，請稍後再試"),

    /**
     * 資料庫服務異常
     */
    DATABASE_ERROR("B0100", "資料庫服務異常");

    /**
     * 錯誤碼
     */
    private final String code;

  /** 錯誤訊息 */
  private final String message;
}
