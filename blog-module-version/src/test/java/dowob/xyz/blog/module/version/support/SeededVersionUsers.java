package dowob.xyz.blog.module.version.support;

import java.util.UUID;

/**
 * 測試資料庫 seed（{@code R__version_test_seed.sql}）內建立的三位使用者，主鍵與公開 UUID 皆為固定值。
 *
 * <p>seed 的 UUID 固定為 {@code 00000000-0000-0000-0000-0000000000bN}（N 為主鍵）。
 * 固定值的原因：version 模組的作者比對（owner／admin 檢查）改以 {@code AuthUser.uuid()} 與快照的
 * {@code author_uuid}（由 article 的 {@code authorUuid} 即 {@code users.uuid} 而來）比對，
 * IT 必須能以同一組 UUID 建立登入身分。與 comment（{@code ...00N}）、reading（{@code ...0000a N}）
 * 模組 seed 的固定值刻意不同，各模組的資料不會被誤認。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class SeededVersionUsers {

    /** 作者 1（AUTHOR）的主鍵 */
    public static final Long USER1_ID = 1L;

    /** 作者 1 的公開 UUID */
    public static final UUID USER1_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    /** 作者 2（AUTHOR）的主鍵 */
    public static final Long USER2_ID = 2L;

    /** 作者 2 的公開 UUID */
    public static final UUID USER2_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    /** 管理員（ADMIN）的主鍵 */
    public static final Long ADMIN_ID = 3L;

    /** 管理員的公開 UUID */
    public static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    /**
     * 依 seed 使用者主鍵推得其固定的公開 UUID（{@code ...0000b0 + id}）。
     *
     * @param userId seed 使用者主鍵（1、2、3）
     * @return 對應的公開 UUID
     */
    public static UUID uuidOf(Long userId) {
        return new UUID(0L, 0xb0L + userId);
    }

    private SeededVersionUsers() {
    }
}
