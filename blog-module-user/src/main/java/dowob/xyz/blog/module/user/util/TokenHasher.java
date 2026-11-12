package dowob.xyz.blog.module.user.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 驗證 token 雜湊工具（A1：{@code verification_tokens} 只存 SHA-256）
 *
 * <p>電子信箱驗證與密碼重設的 token 是 UUID（122 bit 隨機），熵足以讓<b>未加鹽的快速雜湊</b>無法被暴力還原，
 * 因此不需要 bcrypt 之類的慢雜湊（每次驗證都慢雜湊反而讓查詢無法走索引：bcrypt 每次結果不同）。
 * 寫入時存 {@code sha256Hex(明文)}，驗證時對使用者提交的明文做同樣計算後以等值索引查詢。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    /**
     * 計算明文 token 的 SHA-256（小寫 hex，64 字元）
     *
     * @param plainToken 明文 token（不可為 null）
     * @return 64 字元小寫 hex
     * @throws NullPointerException plainToken 為 null
     */
    public static String sha256Hex(String plainToken) {
        Objects.requireNonNull(plainToken, "plainToken");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plainToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            /* 每個 JRE 都必須提供 SHA-256（Java 規格保證），走到這裡代表環境損壞 */
            throw new IllegalStateException("JRE 不支援 SHA-256", e);
        }
    }
}
