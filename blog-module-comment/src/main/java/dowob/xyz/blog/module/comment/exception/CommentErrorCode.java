package dowob.xyz.blog.module.comment.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 留言模組錯誤碼 (Comment Module) 範圍：C01
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum CommentErrorCode implements IErrorCode {

    /**
     * 留言不存在
     */
    COMMENT_NOT_FOUND("C0101", "留言不存在"),

    /**
     * 不可超過 2 層巢狀
     */
    NESTING_TOO_DEEP("C0102", "不可超過 2 層巢狀"),

    /**
     * 編輯時限已過
     */
    EDIT_WINDOW_EXPIRED("C0103", "編輯時限已過"),

    /**
     * 留言已刪除，無法操作
     */
    COMMENT_DELETED("C0104", "留言已刪除，無法操作"),

    /**
     * 父留言不屬於此文章
     */
    PARENT_NOT_IN_ARTICLE("C0105", "父留言不屬於此文章"),

    /**
     * 父留言已凍結
     */
    PARENT_FROZEN("C0106", "父留言已凍結");

    /**
     * 錯誤碼
     */
    private final String code;

    /**
     * 錯誤訊息
     */
    private final String message;
}
