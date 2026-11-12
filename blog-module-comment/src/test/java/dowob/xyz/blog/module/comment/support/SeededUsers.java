package dowob.xyz.blog.module.comment.support;

import java.util.UUID;

/**
 * 測試資料庫 seed（{@code R__comment_test_seed.sql}）內建立的三位使用者，主鍵與公開 UUID 皆為固定值。
 *
 * <p>seed 的 UUID 固定為 {@code 00000000-0000-0000-0000-00000000000N}（N 為主鍵），
 * IT 以這組固定值建立 {@code AuthUser}、寫入留言並斷言，不依賴隨機產生的 UUID。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class SeededUsers {

    /** 一般使用者（USER）的主鍵 */
    public static final Long USER_ID = 1L;

    /** 一般使用者（USER）的公開 UUID */
    public static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 另一位一般使用者（USER）的主鍵 */
    public static final Long OTHER_USER_ID = 2L;

    /** 另一位一般使用者（USER）的公開 UUID */
    public static final UUID OTHER_USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** 管理員（ADMIN）的主鍵 */
    public static final Long ADMIN_ID = 3L;

    /** 管理員（ADMIN）的公開 UUID */
    public static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private SeededUsers() {
    }
}
