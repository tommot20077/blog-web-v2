package dowob.xyz.blog.common.api.errorcode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文章模組錯誤碼 (Article Module)
 * 範圍：A02
 */
@Getter
@AllArgsConstructor
public enum ArticleErrorCode implements IErrorCode {

    ARTICLE_NOT_FOUND("A0201", "文章不存在或已刪除"),
    ARTICLE_PUBLISH_FAILED("A0202", "文章發布失敗");

    private final String code;
    private final String message;

}
