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
     * 請求參數缺失
     */
    REQUEST_PARAM_MISSING("A0004", "請求參數缺失"),

    /**
     * 未認證（未登入或 Token 無效）
     */
    UNAUTHENTICATED("A0005", "請先登入"),

    /**
     * 已認證但無權限存取資源
     */
    FORBIDDEN("A0006", "權限不足"),


    /**
     * 系統執行出錯，請稍後再試
     */
    SYSTEM_EXECUTION_ERROR("B0001", "系統執行出錯，請稍後再試"),

    /**
     * 資料庫服務異常
     */
    DATABASE_ERROR("B0100", "資料庫服務異常"),

    /**
     * 物件儲存服務異常（如 MinIO 上傳/刪除失敗）
     */
    STORAGE_ERROR("B0200", "儲存服務異常，請稍後再試"),

    /**
     * 檔案 I/O 異常（如讀取檔案內容或 MIME 偵測失敗）
     */
    FILE_IO_ERROR("B0201", "檔案處理異常，請稍後再試");

    /**
     * 錯誤碼
     */
    private final String code;

  /** 錯誤訊息 */
  private final String message;
}
