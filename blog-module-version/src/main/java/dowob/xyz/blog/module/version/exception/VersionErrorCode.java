package dowob.xyz.blog.module.version.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Version 模組錯誤碼。
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum VersionErrorCode implements IErrorCode {

    VERSION_NOT_FOUND("V0101", "Version 不存在"),
    VERSION_ACCESS_DENIED("V0102", "不可操作他人的 Version"),
    CANNOT_DELETE_PUBLISHED("V0103", "PUBLISHED 凍結快照不可刪除"),
    CANNOT_PROMOTE_NON_AUTO("V0104", "只有 AUTO 類型可升級為 MANUAL"),
    PREFERENCE_INVALID("V0105", "配置值超出合理範圍"),
    ARTICLE_NOT_FOUND("V0106", "操作的 Article 不存在");

    private final String code;
    private final String message;
}
