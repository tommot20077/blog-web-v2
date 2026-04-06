package dowob.xyz.blog.common.api.errorcode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文章模組錯誤碼 (Article Module) 範圍：A02
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum ArticleErrorCode implements IErrorCode {

    /**
     * 文章不存在或已刪除
     */
    ARTICLE_NOT_FOUND("A0201", "文章不存在或已刪除"),

    /**
     * 文章發布失敗
     */
    ARTICLE_PUBLISH_FAILED("A0202", "文章發布失敗"),

    /**
     * 非文章作者，無操作權限
     */
    ARTICLE_ACCESS_DENIED("A0203", "您無權限操作此文章"),

    /**
     * 非法狀態轉換
     */
    ARTICLE_STATUS_TRANSITION_INVALID("A0204", "文章狀態轉換不合法"),

    /**
     * 分類不存在
     */
    CATEGORY_NOT_FOUND("A0205", "分類不存在"),

    /**
     * 分類下仍有文章，無法刪除
     */
    CATEGORY_HAS_ARTICLES("A0206", "分類下仍有文章，無法刪除"),

    /**
     * 分類 slug 已被使用
     */
    CATEGORY_SLUG_DUPLICATE("A0207", "分類 slug 已被使用"),

    /**
     * 文章並發更新（樂觀鎖衝突）
     */
    ARTICLE_CONCURRENT_UPDATE("A0208", "文章已被其他人修改，請重新整理後再試"),

    /**
     * 目前文章狀態不允許編輯（PENDING_REVIEW / PUBLISHED / ARCHIVED）
     */
    ARTICLE_EDIT_NOT_ALLOWED("A0209", "目前狀態不允許編輯");

    /**
     * 錯誤碼
     */
    private final String code;

    /**
     * 錯誤訊息
     */
    private final String message;
}
