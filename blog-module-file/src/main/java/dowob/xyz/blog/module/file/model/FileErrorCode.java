package dowob.xyz.blog.module.file.model;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 檔案模組錯誤碼（File Module）範圍：F
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum FileErrorCode implements IErrorCode {

    /**
     * 檔案不存在
     */
    FILE_NOT_FOUND("F001", "檔案不存在"),

    /**
     * 檔案大小超過限制
     */
    FILE_TOO_LARGE("F002", "檔案大小超過限制"),

    /**
     * 儲存空間配額已滿
     */
    QUOTA_EXCEEDED("F003", "儲存空間配額已滿"),

    /**
     * 不支援的檔案類型
     */
    INVALID_FILE_TYPE("F004", "不支援的檔案類型"),

    /**
     * 無權限操作此檔案
     */
    FILE_ACCESS_DENIED("F005", "無權限操作此檔案");

    /**
     * 錯誤碼
     */
    private final String code;

    /**
     * 錯誤訊息
     */
    private final String message;
}
