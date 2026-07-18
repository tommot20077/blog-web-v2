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

    /**
     * 以 status name（String）判斷是否公開可見。
     *
     * <p>跨模組傳遞時 status 常以 String 形式流通（例如 {@code ArticleData.status()}），
     * 為避免各 caller 各自硬寫「等於 PUBLISHED」而讓可見性政策漂移，統一委派 {@link #isPubliclyVisible()}。
     * null 或無法對應到列舉的字串一律視為不公開（不拋例外）。</p>
     *
     * @param statusName 文章狀態名稱（可為 null）
     * @return 對應狀態公開可見時回傳 true；null / 未知字串回傳 false
     */
    public static boolean isPubliclyVisible(String statusName) {
        if (statusName == null) {
            return false;
        }
        try {
            return valueOf(statusName).isPubliclyVisible();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
