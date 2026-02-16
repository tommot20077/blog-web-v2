package dowob.xyz.blog.module.tag.model;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 標籤模組錯誤碼枚舉
 *
 * <p>
 * 定義標籤相關業務異常的錯誤碼與訊息，實作 {@link IErrorCode} 介面，
 * 供 {@link dowob.xyz.blog.common.exception.BusinessException} 使用。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum TagErrorCode implements IErrorCode {

    /** 標籤不存在 */
    TAG_NOT_FOUND("T001", "標籤不存在"),

    /** 標籤名稱已存在 */
    TAG_NAME_CONFLICT("T002", "標籤名稱已存在"),

    /** 標籤正在使用中，無法刪除 */
    TAG_IN_USE("T003", "標籤正在使用中，無法刪除"),

    /** 標籤 Slug 已存在 */
    TAG_SLUG_CONFLICT("T004", "標籤 Slug 已存在"),

    /** 標籤名稱不能為空或只含特殊符號 */
    TAG_INVALID_NAME("T005", "標籤名稱不能為空");

    /**
     * 錯誤碼
     */
    private final String code;

    /**
     * 錯誤訊息
     */
    private final String message;
}
