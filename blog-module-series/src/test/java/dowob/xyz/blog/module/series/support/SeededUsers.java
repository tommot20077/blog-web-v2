package dowob.xyz.blog.module.series.support;

import java.util.UUID;

/**
 * series 模組 IT 的固定測試使用者（對應 {@code R__series_test_seed.sql}）。
 *
 * <p>seed 以固定 uuid 寫入 {@code users}，IT 即可用同一組值建立 {@code AuthUser}、寫入
 * {@code series.author_uuid} 並斷言；文章的 {@code authorUuid} 由 article 模組以 {@code users.uuid} 解析，
 * 因此兩邊必須是同一個值，授權比對才會成立。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class SeededUsers {

    /** 使用者 1（AUTHOR）的主鍵 */
    public static final Long USER1_ID = 1L;

    /** 使用者 2（AUTHOR）的主鍵 */
    public static final Long USER2_ID = 2L;

    /** 管理員（ADMIN）的主鍵 */
    public static final Long ADMIN_ID = 3L;

    /** 使用者 1 的公開 UUID */
    public static final UUID USER1_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    /** 使用者 2 的公開 UUID */
    public static final UUID USER2_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    /** 管理員的公開 UUID */
    public static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    /** 工具類別，禁止實例化。 */
    private SeededUsers() {
    }
}
