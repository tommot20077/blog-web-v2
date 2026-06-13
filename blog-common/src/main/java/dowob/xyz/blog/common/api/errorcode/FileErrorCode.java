package dowob.xyz.blog.common.api.errorcode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 檔案模組錯誤碼（File Module）範圍：A04
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum FileErrorCode implements IErrorCode {

    /** 檔案不存在 */
    FILE_NOT_FOUND("A0401", "檔案不存在"),

    /** 檔案大小超過限制 */
    FILE_TOO_LARGE("A0402", "檔案大小超過限制"),

    /** 儲存空間配額已滿 */
    QUOTA_EXCEEDED("A0403", "儲存空間配額已滿"),

    /** 不支援的檔案類型 */
    INVALID_FILE_TYPE("A0404", "不支援的檔案類型"),

    /** 無權限操作此檔案 */
    FILE_ACCESS_DENIED("A0405", "無權限操作此檔案");

    /**
     * 錯誤碼
     */
    private final String code;

    /**
     * 錯誤訊息
     */
    private final String message;
}
