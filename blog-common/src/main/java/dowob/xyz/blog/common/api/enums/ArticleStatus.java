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

    /**
     * 作者刪除帳號（匿名化，DATA-02）時，處於此狀態的文章是否隨之移除。
     *
     * <p>Q2 定案：保留已發布文章（作者顯示因 users 列匿名化而自動變成匿名暱稱）、刪除草稿與待審。
     * 本方法是「去留」的唯一真相，article 模組（決定刪哪些文章）與 file 模組（決定哪些上傳檔案仍被保留文章引用）
     * 共用，避免兩邊各自硬寫狀態集合而漂移：</p>
     * <ul>
     *   <li>{@link #DRAFT}、{@link #PENDING_REVIEW}、{@link #REJECTED}：未公開、沒有保留理由 → 移除；</li>
     *   <li>{@link #PUBLISHED}：公開內容 → 保留；</li>
     *   <li>{@link #ARCHIVED}：已下架，保留。下架用於法務／侵權撤下，內容可能需要留存，
     *       不應由使用者刪帳連動銷毀；它不公開，保留不構成對外暴露。</li>
     * </ul>
     *
     * @return DRAFT、PENDING_REVIEW、REJECTED 回傳 true
     */
    public boolean isRemovedWithAuthorAccount() {
        return this == DRAFT || this == PENDING_REVIEW || this == REJECTED;
    }

    /**
     * 以 status name（String）判斷作者刪帳時是否移除該狀態的文章（見 {@link #isRemovedWithAuthorAccount()}）。
     *
     * <p>跨模組傳遞時 status 常以 String 形式流通（例如 {@code ArticleData.status()}）。
     * null 或無法對應到列舉的字串一律視為<b>不移除</b>：無法確認時寧可保留，也不銷毀資料。</p>
     *
     * @param statusName 文章狀態名稱（可為 null）
     * @return 對應狀態應移除時回傳 true；null／未知字串回傳 false
     */
    public static boolean isRemovedWithAuthorAccount(String statusName) {
        if (statusName == null) {
            return false;
        }
        try {
            return valueOf(statusName).isRemovedWithAuthorAccount();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
