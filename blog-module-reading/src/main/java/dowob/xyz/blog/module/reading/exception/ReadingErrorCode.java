package dowob.xyz.blog.module.reading.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Reading 模組錯誤碼。範圍：R02 / R03
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum ReadingErrorCode implements IErrorCode {

    /** Highlight 不存在 */
    HIGHLIGHT_NOT_FOUND("R0201", "Highlight 不存在"),

    /** 不可變更他人的 Highlight */
    HIGHLIGHT_ACCESS_DENIED("R0202", "不可變更他人的 Highlight"),

    /** 進度值超出範圍 */
    PROGRESS_OUT_OF_RANGE("R0301", "進度值超出範圍");

    private final String code;
    private final String message;
}
