package dowob.xyz.blog.module.user.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 驗證 Token 實體
 *
 * <p>用於儲存電子信箱驗證與密碼重設所需的一次性 Token（只存雜湊），
 * 每個 Token 對應一位用戶，並帶有類型與有效期限欄位。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Table("verification_tokens")
public class VerificationToken {

    /**
     * 主鍵 ID
     */
    @Id
    private Long id;

    /**
     * 關聯的用戶 ID
     */
    @Column("user_id")
    private Long userId;

    /**
     * Token 的 SHA-256 雜湊（64 字元小寫 hex）。<b>資料庫永遠不存明文 token</b>，明文只出現在寄出的信件中。
     */
    @Column("token_hash")
    private String tokenHash;

    /**
     * Token 類型，如 "EMAIL_VERIFICATION" 或 "PASSWORD_RESET"
     */
    private String type;

    /**
     * Token 過期時間
     */
    @Column("expires_at")
    private LocalDateTime expiresAt;

    /**
     * 建立時間
     */
    @Column("created_at")
    private LocalDateTime createdAt;
}
