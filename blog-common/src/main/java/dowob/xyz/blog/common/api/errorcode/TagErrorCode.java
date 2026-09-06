package dowob.xyz.blog.common.api.errorcode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 標籤模組錯誤碼 (Tag Module) 範圍：A03
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum TagErrorCode implements IErrorCode {

    /** 標籤不存在 */
    TAG_NOT_FOUND("A0301", "標籤不存在"),

    /** 標籤名稱已存在 */
    TAG_NAME_CONFLICT("A0302", "標籤名稱已存在"),

    /** 標籤正在使用中，無法刪除 */
    TAG_IN_USE("A0303", "標籤正在使用中，無法刪除"),

    /** 標籤 Slug 已存在 */
    TAG_SLUG_CONFLICT("A0304", "標籤 Slug 已存在"),

    /** 標籤名稱不能為空或只含特殊符號 */
    TAG_INVALID_NAME("A0305", "標籤名稱不能為空");

    /**
     * 錯誤碼
     */
    private final String code;

    /**
     * 錯誤訊息
     */
    private final String message;
}
