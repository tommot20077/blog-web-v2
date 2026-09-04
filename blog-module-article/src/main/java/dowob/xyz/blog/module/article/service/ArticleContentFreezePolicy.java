package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;

import java.util.Set;

/**
 * 文章「內容凍結」政策 —— 內容可否被改寫的<b>唯一真相</b>
 *
 * <p>
 * 規則：只有 {@link ArticleStatus#DRAFT} 與 {@link ArticleStatus#REJECTED} 允許改寫文章內容；
 * {@code PENDING_REVIEW} / {@code PUBLISHED} / {@code ARCHIVED} 一律凍結，違反時拋
 * {@link ArticleErrorCode#ARTICLE_EDIT_NOT_ALLOWED}（A0209）。
 * </p>
 *
 * <p>
 * <b>為何要抽成共用政策（F-H1）</b>：此判斷原本只內嵌在
 * {@code ArticleCommandSubService#updateArticle}（PUT 路徑），
 * 版本還原（{@code ArticleFacadeImpl#applyRestoreContent}）完全不受約束，形成審核 TOCTOU——
 * 作者送審後仍可用還原把內容換掉，admin 審的是 A、通過的是 B。
 * 修法若在還原路徑<b>複製</b>一份相同判斷，就會再造出「多套真相」，
 * 正是 SEC-02（PR #66）剛把狀態轉換守衛收斂掉的問題；故改為兩條寫入路徑共用本政策。
 * 未來新增任何會改寫 {@code article.content} 的路徑，一律呼叫
 * {@link #assertContentEditable(ArticleStatus)}，不得自行內嵌狀態判斷。
 * </p>
 *
 * <p>
 * <b>與狀態機的分工</b>：{@code ArticleCommandSubService.VALID_TRANSITIONS} 管「狀態能怎麼轉」，
 * 本政策管「什麼狀態下內容能被改寫」；兩者互不重疊，還原路徑只碰後者（SEC-02 後還原已無法改狀態）。
 * </p>
 *
 * <p>
 * 刻意設計為無狀態的靜態工具（非 Spring bean）：本規則是 {@link ArticleStatus} 的純函式，
 * 無任何相依，做成 bean 只會讓兩個呼叫端的建構子與其單元測試多背一個 mock。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class ArticleContentFreezePolicy {

    /**
     * 允許改寫內容的狀態集合（其餘狀態一律凍結）
     */
    private static final Set<ArticleStatus> CONTENT_EDITABLE_STATUSES =
            Set.of(ArticleStatus.DRAFT, ArticleStatus.REJECTED);

    /**
     * 工具類別，禁止實例化
     */
    private ArticleContentFreezePolicy() {
    }

    /**
     * 判斷該狀態下文章內容是否允許改寫
     *
     * @param status 文章目前狀態（可為 null）
     * @return DRAFT / REJECTED 回傳 true；其餘狀態與 null 回傳 false
     */
    public static boolean isContentEditable(ArticleStatus status) {
        return status != null && CONTENT_EDITABLE_STATUSES.contains(status);
    }

    /**
     * 內容凍結守衛：狀態不允許改寫內容時拋出業務例外
     *
     * <p>null 狀態視為凍結（fail-safe），與抽出前 PUT 守衛
     * {@code status != DRAFT && status != REJECTED} 的行為一致。</p>
     *
     * @param status 文章目前狀態（可為 null）
     * @throws BusinessException ARTICLE_EDIT_NOT_ALLOWED（A0209）當狀態處於內容凍結
     */
    public static void assertContentEditable(ArticleStatus status) {
        if (!isContentEditable(status)) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_EDIT_NOT_ALLOWED);
        }
    }
}
