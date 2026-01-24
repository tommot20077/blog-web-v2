package dowob.xyz.blog.common.api.errorcode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用/系統錯誤碼 (Common & System)
 * 包含：成功(00000)、通用參數錯誤(A00)、系統錯誤(B00)
 */
@Getter
@AllArgsConstructor
public enum CommonErrorCode implements IErrorCode {
    
    // 00000: 成功
    SUCCESS("00000", "操作成功"),

    // A00: 通用/基礎錯誤 (用戶端責任)
    PARAM_VALID_ERROR("A0001", "參數校驗失敗"),
    REQUEST_METHOD_NOT_SUPPORTED("A0002", "不支持的請求方法"),
    
    // B00: 系統通用錯誤 (服務端責任)
    SYSTEM_EXECUTION_ERROR("B0001", "系統執行出錯，請稍後再試"),
    
    // B01: 資料庫/存儲
    DATABASE_ERROR("B0100", "資料庫服務異常");

    private final String code;
    private final String message;
}
