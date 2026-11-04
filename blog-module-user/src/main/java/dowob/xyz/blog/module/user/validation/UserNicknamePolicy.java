package dowob.xyz.blog.module.user.validation;

import java.text.Normalizer;

/**
 * 暱稱政策：保留「已刪除使用者」前綴，防止使用者假冒已刪除帳號。
 *
 * <p>DATA-02 會把被刪除帳號的暱稱改為 {@code 已刪除使用者#<uuid 前 8 碼>}。若一般使用者可自取以
 * {@link #DELETED_NICKNAME_PREFIX} 開頭的暱稱，就能偽裝成「已刪除帳號」的留言／文章作者。
 * 因此註冊與修改暱稱都必須拒絕此前綴；DATA-02 的刪除流程也應引用同一個常數產生暱稱，避免兩處漂移。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class UserNicknamePolicy {

    /** 已刪除帳號暱稱的保留前綴（DATA-02 的刪除流程共用） */
    public static final String DELETED_NICKNAME_PREFIX = "已刪除使用者";

    private UserNicknamePolicy() {
    }

    /**
     * 判斷暱稱是否為保留暱稱（以已刪除使用者前綴開頭）。
     *
     * <p>先做 NFKC 正規化（全形／相容字元轉為標準形），再移除所有空白（半形、全形、各種 Unicode 空白）
     * 與零寬等格式字元（{@code \p{Cf}}），最後比對前綴，以涵蓋「已刪除 使用者」「  已刪除使用者」等變體。</p>
     *
     * @param nickname 暱稱，可為 null
     * @return 是保留暱稱回傳 true
     */
    public static boolean isReserved(String nickname) {
        if (nickname == null) {
            return false;
        }
        String normalized = Normalizer.normalize(nickname, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s\\p{Cf}]", "");
        return normalized.startsWith(DELETED_NICKNAME_PREFIX);
    }
}
