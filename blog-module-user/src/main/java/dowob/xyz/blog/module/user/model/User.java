package dowob.xyz.blog.module.user.model;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用戶實體
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Table("users")
public class User {
    @Id
    private Long id;

    /**
     * UUID (對外公開ID)
     */
    private UUID uuid;

    /**
     * 電子信箱
     */
    private String email;

    /**
     * 密碼雜湊
     */
    @Column("password_hash")
    private String passwordHash;

    /**
     * 暱稱
     */
    private String nickname;

    /**
     * 頭貼 URL
     */
    @Column("avatar_url")
    private String avatarUrl;

    /**
     * 個人簡介
     */
    private String bio;

    /**
     * 角色
     */
    private Role role;

    /**
     * 狀態
     */
    private UserStatus status;

    /**
     * 信箱是否已驗證
     */
    @Column("email_verified")
    private boolean emailVerified;

    /**
     * Token 版本號 (用於 JWT 撤銷)
     */
    @Column("token_version")
    private String tokenVersion;

    /**
     * 創建時間
     */
    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新時間
     */
    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
