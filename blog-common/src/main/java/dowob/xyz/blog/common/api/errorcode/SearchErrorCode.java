package dowob.xyz.blog.common.api.errorcode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 搜尋模組錯誤碼（A05xx）
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum SearchErrorCode implements IErrorCode {

    /**
     * 已有搜尋索引全量重建作業執行中（對應 HTTP 409）
     */
    REINDEX_IN_PROGRESS("A0501", "已有搜尋索引重建作業執行中"),

    /**
     * 已通過認證但 principal 無法解析為 AuthUser（對應 HTTP 400）
     */
    PRINCIPAL_UNRESOLVED("A0502", "無法識別當前登入用戶");

    /**
     * 錯誤碼
     */
    private final String code;

    /**
     * 錯誤訊息
     */
    private final String message;
}
