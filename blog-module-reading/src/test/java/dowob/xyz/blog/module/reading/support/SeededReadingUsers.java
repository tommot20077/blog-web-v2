package dowob.xyz.blog.module.reading.support;

import java.util.UUID;

/**
 * 測試資料庫 seed（{@code R__reading_test_seed.sql}）內建立的三位使用者，主鍵與公開 UUID 皆為固定值。
 *
 * <p>seed 的 UUID 固定為 {@code 00000000-0000-0000-0000-0000000000aN}（N 為主鍵）。
 * 固定值的原因：收藏列表的可見性判斷（作者本人／ADMIN 看得到下架文章）是以
 * {@code AuthUser.uuid()} 與 {@code users.uuid} 比對，IT 必須能以同一組 UUID 建立登入身分。
 * 與 comment 模組 seed 的 {@code ...00N} 刻意不同，兩個模組的資料不會被誤認。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class SeededReadingUsers {

    /** 一般使用者（USER）的主鍵 */
    public static final Long USER_ID = 1L;

    /** 一般使用者（USER）的公開 UUID */
    public static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /** 另一位一般使用者（USER）的主鍵 */
    public static final Long OTHER_USER_ID = 2L;

    /** 另一位一般使用者（USER）的公開 UUID */
    public static final UUID OTHER_USER_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    /** 管理員（ADMIN）的主鍵 */
    public static final Long ADMIN_ID = 3L;

    /** 管理員（ADMIN）的公開 UUID */
    public static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

    /**
     * 依 seed 使用者主鍵推得其固定的公開 UUID（{@code ...0000a0 + id}）。
     *
     * @param userId seed 使用者主鍵（1、2、3）
     * @return 對應的公開 UUID
     */
    public static UUID uuidOf(Long userId) {
        return new UUID(0L, 0xa0L + userId);
    }

    private SeededReadingUsers() {
    }
}
