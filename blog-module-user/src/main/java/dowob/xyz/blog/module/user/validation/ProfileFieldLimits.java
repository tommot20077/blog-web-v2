package dowob.xyz.blog.module.user.validation;

/**
 * 個人資料欄位（暱稱、URL 類）的長度上限常數
 *
 * <p>與 DB 欄位長度一致（見 {@code ai-docs/schema.md} users 表：nickname VARCHAR(50)、website VARCHAR(255)、avatar_url VARCHAR(512)），
 * 供 DTO 註解與 service 層防禦縱深檢查共用，避免兩處各寫一份而漂移。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class ProfileFieldLimits {

    /** website 長度上限（對應 users.website VARCHAR(255)） */
    public static final int WEBSITE_MAX_LENGTH = 255;

    /** avatarUrl 長度上限（對應 users.avatar_url VARCHAR(512)） */
    public static final int AVATAR_URL_MAX_LENGTH = 512;

    /** nickname 長度上限（對應 users.nickname VARCHAR(50)；註冊與改暱稱共用） */
    public static final int NICKNAME_MAX_LENGTH = 50;

    private ProfileFieldLimits() {
    }
}
