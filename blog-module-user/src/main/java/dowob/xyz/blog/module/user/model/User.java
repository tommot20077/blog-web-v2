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

    /**
     * 資料庫主鍵 ID
     */
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
     * 用戶名（登入識別符，唯一）
     */
    private String username;

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
     * 個人網站 URL
     */
    private String website;

    /**
     * 社群連結（JSONB 以 String 儲存）
     */
    @Column("social_links")
    private String socialLinks;

    /**
     * 所在地（自由文字，可為 null）
     */
    private String location;

    /**
     * 通知偏好：留言通知。
     *
     * <p>預設初始化為 true。Spring Data JDBC 對未設值欄位會送明確 NULL，
     * 覆蓋 DB DEFAULT，故此處必須在 Java 端預設為 true，避免新使用者 insert
     * 送 NULL 違反 NOT NULL 約束。</p>
     */
    @Column("notification_comment")
    private boolean notificationComment = true;

    /**
     * 通知偏好：按讚通知（預設 true，理由同 {@link #notificationComment}）。
     */
    @Column("notification_like")
    private boolean notificationLike = true;

    /**
     * 通知偏好：審核結果通知（預設 true，理由同 {@link #notificationComment}）。
     */
    @Column("notification_review")
    private boolean notificationReview = true;

    /**
     * 通知偏好：追蹤通知（預設 true，理由同 {@link #notificationComment}）。
     */
    @Column("notification_follow")
    private boolean notificationFollow = true;

    /**
     * 通知偏好：電子報訂閱（預設 true，理由同 {@link #notificationComment}）。
     */
    @Column("notification_newsletter")
    private boolean notificationNewsletter = true;

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
