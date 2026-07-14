package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.UserStatus;

import dowob.xyz.blog.module.user.util.TokenVersionUtils;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.dto.response.UserProfileResponse;
import dowob.xyz.blog.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用戶自助服務
 *
 * <p>提供已登入用戶的自助操作功能，包含更新個人資料、修改密碼與刪除帳號。
 * 修改密碼與刪除帳號會遞增 tokenVersion，使所有現有 JWT Token 立即失效。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class UserService {

    /** 用戶資料存取 */
    private final UserRepository userRepository;

    /** 密碼加密器 */
    private final PasswordEncoder passwordEncoder;

    /** Session 撤銷服務，用於改密碼／刪帳號後撤銷所有既有 session */
    private final SessionRevoker sessionRevoker;

    /**
     * 取得使用者個人資料
     *
     * <p>依 userId 查詢使用者，並回傳對外公開的個人資料。若使用者不存在，拋出 BusinessException。</p>
     *
     * @param userId 用戶 ID
     * @return 使用者個人資料 DTO
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        return new UserProfileResponse(
                user.getUuid(),
                user.getEmail(),
                user.getNickname(),
                user.getBio(),
                user.getAvatarUrl(),
                user.getWebsite(),
                user.getSocialLinks(),
                user.getLocation(),
                user.getRole(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.isNotificationComment(),
                user.isNotificationLike(),
                user.isNotificationReview(),
                user.isNotificationFollow(),
                user.isNotificationNewsletter());
    }

    /**
     * 更新個人資料
     *
     * <p>更新暱稱、個人簡介、個人網站、社群連結、頭貼與所在地；
     * 若新暱稱已被其他用戶使用，拋出 BusinessException。</p>
     *
     * @param userId      用戶 ID
     * @param nickname    新的暱稱
     * @param bio         新的個人簡介（可為 null）
     * @param website     個人網站 URL（可為 null）
     * @param socialLinks 社群連結 JSON 字串（可為 null）
     * @param avatarUrl   頭貼 URL（可為 null）
     * @param location    所在地（可為 null）
     */
    @Transactional
    public void updateProfile(Long userId, String nickname, String bio, String website, String socialLinks,
                              String avatarUrl, String location) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (!user.getNickname().equals(nickname) && userRepository.existsByNickname(nickname)) {
            throw new BusinessException(UserErrorCode.NICKNAME_DUPLICATED);
        }

        user.setNickname(nickname);
        user.setBio(bio);
        user.setWebsite(website);
        user.setSocialLinks(socialLinks);
        user.setAvatarUrl(avatarUrl);
        user.setLocation(location);
        userRepository.save(user);
    }

    /**
     * 更新通知偏好
     *
     * <p>更新 5 個通知偏好開關（留言、按讚、審核、追蹤、電子報）。</p>
     *
     * @param userId     用戶 ID
     * @param comment    留言通知偏好
     * @param like       按讚通知偏好
     * @param review     審核結果通知偏好
     * @param follow     追蹤通知偏好
     * @param newsletter 電子報訂閱偏好
     */
    @Transactional
    public void updateNotificationPreferences(Long userId, boolean comment, boolean like,
                                              boolean review, boolean follow, boolean newsletter) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        user.setNotificationComment(comment);
        user.setNotificationLike(like);
        user.setNotificationReview(review);
        user.setNotificationFollow(follow);
        user.setNotificationNewsletter(newsletter);
        userRepository.save(user);
    }

    /**
     * 修改密碼
     *
     * <p>驗證舊密碼正確後更新密碼雜湊並遞增 tokenVersion，隨即撤銷該用戶所有既有 session
     * （清除 Redis auth hash 與 refresh ZSet），使既有 Access Token 與 Refresh Token 立即失效。</p>
     *
     * @param userId      用戶 ID
     * @param oldPassword 當前舊密碼（明文）
     * @param newPassword 新密碼（明文）
     */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.USER_PASSWORD_ERROR);
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        String newVersion = TokenVersionUtils.incrementVersion(user.getTokenVersion());
        user.setTokenVersion(newVersion);
        userRepository.save(user);

        sessionRevoker.revokeAllSessions(userId);
    }

    /**
     * 刪除帳號
     *
     * <p>驗證密碼正確後將帳號狀態設為 DELETED，並清除 Redis 中的所有快取，
     * 使所有現有 Token 立即失效。</p>
     *
     * @param userId   用戶 ID
     * @param password 當前密碼（明文），用於二次確認身份
     */
    @Transactional
    public void deleteAccount(Long userId, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.USER_PASSWORD_ERROR);
        }

        user.setStatus(UserStatus.DELETED);
        userRepository.save(user);

        sessionRevoker.revokeAllSessions(userId);
    }
}
