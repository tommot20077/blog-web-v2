package dowob.xyz.blog.common.api.enums;

/**
 * 文章狀態枚舉
 *
 * <p>
 * 定義文章的生命週期狀態，控制可見性與流程轉換。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public enum ArticleStatus {

    /**
     * 草稿：僅作者可見，尚未發布
     */
    DRAFT,

    /**
     * 待審核：已提交審核，等待管理員審核
     */
    PENDING_REVIEW,

    /**
     * 已發布：公開可見
     */
    PUBLISHED,

    /**
     * 已封存：不再公開，僅作者與管理員可見
     */
    ARCHIVED,

    /**
     * 已駁回：管理員駁回審核，僅作者可見
     */
    REJECTED;

    /**
     * 判斷文章是否公開可見
     *
     * @return 僅 PUBLISHED 狀態回傳 true
     */
    public boolean isPubliclyVisible() {
        return this == PUBLISHED;
    }
}
