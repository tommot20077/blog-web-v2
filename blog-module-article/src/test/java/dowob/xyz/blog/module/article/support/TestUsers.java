package dowob.xyz.blog.module.article.support;

import java.util.UUID;

/**
 * article 模組測試用的登入身分對照。
 *
 * <p>測試建立的 {@code AuthUser} 的 {@code uuid} 必須與測試資料的使用者主鍵 {@code id} <b>不同構</b>：
 * 若 uuid 是 {@code new UUID(0L, id)} 這類由 id 直接推得的值，controller 誤把使用者主鍵轉成 uuid
 * 傳給 service 時結果碰巧相同，授權相關測試就殺不掉這種變異。這裡的 uuid 是固定常數
 * （{@code 00000000-0000-0000-0000-000000000dNN}），與 id 的編碼不同，且不與其他模組 seed 使用的固定值
 * （{@code …00N}、{@code …0a?}、{@code …0b?}、{@code …0c?}）重複。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class TestUsers {

    /** 工具類別，禁止實例化。 */
    private TestUsers() {
    }

    /**
     * 取得測試使用者 id 對應的固定公開 UUID。
     *
     * @param id 測試使用者主鍵（0–255）
     * @return 固定 UUID；與 {@code new UUID(0L, id)} 不相等
     */
    public static UUID uuidOf(long id) {
        if (id < 0 || id > 0xFF) {
            throw new IllegalArgumentException("測試使用者 id 超出範圍: " + id);
        }
        return UUID.fromString(String.format("00000000-0000-0000-0000-000000000d%02x", id));
    }
}
