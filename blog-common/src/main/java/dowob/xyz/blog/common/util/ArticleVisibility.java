package dowob.xyz.blog.common.util;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import lombok.experimental.UtilityClass;

import java.util.Objects;
import java.util.UUID;

/**
 * 文章可見性判斷工具類
 *
 * <p>集中「誰可以讀到一篇非公開文章」這條政策，避免各模組各自硬寫狀態比較而讓政策漂移
 * （同 {@link ArticleStatus#isPubliclyVisible(String)} 的用意）。政策本身與
 * {@code ArticleQuerySubService#checkReadPermission}（文章詳情端點）完全一致：</p>
 *
 * <ol>
 *   <li>PUBLISHED —— 任何人（含匿名）可讀；</li>
 *   <li>非 PUBLISHED（DRAFT / PENDING_REVIEW / REJECTED / <b>ARCHIVED</b>）—— 僅作者本人與 ADMIN 可讀。</li>
 * </ol>
 *
 * <p><b>下架語意</b>：ARCHIVED 用於法務／侵權撤下等情境，文章必須從所有公開面消失。
 * 任何以文章為主體的跨模組讀取路徑（留言列表、收藏列表等），若 caller 端沒有套用本判斷，
 * 就等於下架沒下乾淨。</p>
 *
 * @author Yuan
 * @version 2.0（移除 Long 版）
 */
@UtilityClass
public class ArticleVisibility {

    /**
     * 判斷指定檢視者是否可讀取該文章。
     *
     * <p>跨模組識別一律是公開 UUID：作者與檢視者都以 UUID 比對。</p>
     *
     * @param statusName 文章狀態名稱（跨模組以 String 流通，可為 null；null／未知字串視為不公開）
     * @param authorUuid 文章作者公開 UUID（可為 null）
     * @param viewerUuid 檢視者公開 UUID；匿名為 null
     * @param isAdmin    檢視者是否為 ADMIN
     * @return 可讀取回傳 true
     */
    public static boolean isReadableBy(String statusName, UUID authorUuid, UUID viewerUuid, boolean isAdmin) {
        if (ArticleStatus.isPubliclyVisible(statusName)) {
            return true;
        }
        if (isAdmin) {
            return true;
        }
        return viewerUuid != null && Objects.equals(authorUuid, viewerUuid);
    }

    /**
     * 判斷指定檢視者是否可讀取該文章（enum 版，給持有 {@link ArticleStatus} 的模組內 caller 用）。
     *
     * @param status     文章狀態（可為 null；視為不公開）
     * @param authorUuid 文章作者公開 UUID（可為 null）
     * @param viewerUuid 檢視者公開 UUID；匿名為 null
     * @param isAdmin    檢視者是否為 ADMIN
     * @return 可讀取回傳 true
     */
    public static boolean isReadableBy(ArticleStatus status, UUID authorUuid, UUID viewerUuid, boolean isAdmin) {
        return isReadableBy(status == null ? null : status.name(), authorUuid, viewerUuid, isAdmin);
    }
}
