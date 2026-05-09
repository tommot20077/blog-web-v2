package dowob.xyz.blog.module.series.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Series 模組錯誤碼。
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum SeriesErrorCode implements IErrorCode {

    SERIES_NOT_FOUND("S0101", "Series 不存在"),
    SERIES_ACCESS_DENIED("S0102", "不可變更他人的 Series"),
    ARTICLE_NOT_PUBLISHED("S0103", "文章必須為 PUBLISHED 才能加入 Series"),
    SLUG_ALREADY_USED("S0104", "Slug 已被使用"),
    ARTICLE_NOT_IN_SERIES("S0105", "文章不屬於此 Series"),
    ARTICLE_IN_OTHER_SERIES("S0106", "文章已屬於另一個 Series"),
    ARTICLE_NOT_FOUND("S0107", "文章不存在");

    private final String code;
    private final String message;
}
