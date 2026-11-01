package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.UserStatus;

import dowob.xyz.blog.module.user.util.TokenVersionUtils;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.dto.response.UserProfileResponse;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.validation.ProfileFieldLimits;
import dowob.xyz.blog.module.user.validation.SafeHttpUrlValidator;
import dowob.xyz.blog.module.user.validation.SafeSocialLinksValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
     * <p>依 userUuid 查詢使用者，並回傳對外公開的個人資料。若使用者不存在，拋出 BusinessException。</p>
     *
     * @param userUuid 用戶 UUID
     * @return 使用者個人資料 DTO
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(UUID userUuid) {
        User user = userRepository.findByUuid(userUuid)
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
     * <p><b>併發改名（RACE-02）</b>：{@code existsByNickname} 先查只是快速路徑；兩個請求同時改成同一暱稱時
     * 都會通過先查，由 DB 的 {@code uq_users_nickname} 兜底。UPDATE（{@code save}）是本方法最後一個 DB 操作，
     * 撞到約束就地轉成 {@link UserErrorCode#NICKNAME_DUPLICATED}，之後不再執行任何 SQL（交易隨例外回滾）。</p>
     *
     * @param userUuid    用戶 UUID
     * @param nickname    新的暱稱
     * @param bio         新的個人簡介（可為 null）
     * @param website     個人網站 URL（可為 null）
     * @param socialLinks 社群連結 JSON 字串（可為 null）
     * @param avatarUrl   頭貼 URL（可為 null）
     * @param location    所在地（可為 null）
     * @throws BusinessException website／avatarUrl／socialLinks 未通過 SEC-10 安全驗證時
     *                           （{@link CommonErrorCode#PARAM_VALID_ERROR}）
     */
    @Transactional
    public void updateProfile(UUID userUuid, String nickname, String bio, String website, String socialLinks,
                              String avatarUrl, String location) {
        validateProfileUrlFields(website, socialLinks, avatarUrl);

        User user = userRepository.findByUuid(userUuid)
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
        try {
            userRepository.save(user);
        } catch (RuntimeException e) {
            if (UniqueConstraintViolations.constraintNameOf(e)
                    .filter(UniqueConstraintViolations.USERS_NICKNAME::equals).isPresent()) {
                throw new BusinessException(UserErrorCode.NICKNAME_DUPLICATED);
            }
            throw e;
        }
    }

    /**
     * 個人資料 URL 類欄位的防禦縱深檢查（SEC-10）
     *
     * <p>與 controller 的 {@code @Valid} 使用同一份驗證邏輯，避免其他呼叫路徑（內部呼叫、未來新增的入口）
     * 繞過協定白名單。在任何 DB 存取之前執行；失敗一律回傳既有的 {@link CommonErrorCode#PARAM_VALID_ERROR}，
     * 不新增前端未知的錯誤碼。</p>
     *
     * @param website     個人網站 URL
     * @param socialLinks 社群連結 JSON 字串
     * @param avatarUrl   頭貼 URL
     */
    private void validateProfileUrlFields(String website, String socialLinks, String avatarUrl) {
        if (!SafeHttpUrlValidator.isSafe(website, ProfileFieldLimits.WEBSITE_MAX_LENGTH, false)) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR, "website: 必須是合法的 http/https 網址");
        }
        if (!SafeHttpUrlValidator.isSafe(avatarUrl, ProfileFieldLimits.AVATAR_URL_MAX_LENGTH, true)) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR, "avatarUrl: 必須是合法的 http/https 網址");
        }
        if (!SafeSocialLinksValidator.isSafe(socialLinks)) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR, "socialLinks: 社群連結格式不正確");
        }
    }

    /**
     * 更新通知偏好
     *
     * <p>更新 5 個通知偏好開關（留言、按讚、審核、追蹤、電子報）。</p>
     *
     * @param userUuid   用戶 UUID
     * @param comment    留言通知偏好
     * @param like       按讚通知偏好
     * @param review     審核結果通知偏好
     * @param follow     追蹤通知偏好
     * @param newsletter 電子報訂閱偏好
     */
    @Transactional
    public void updateNotificationPreferences(UUID userUuid, boolean comment, boolean like,
                                              boolean review, boolean follow, boolean newsletter) {
        User user = userRepository.findByUuid(userUuid)
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
     * @param userUuid    用戶 UUID
     * @param oldPassword 當前舊密碼（明文）
     * @param newPassword 新密碼（明文）
     */
    @Transactional
    public void changePassword(UUID userUuid, String oldPassword, String newPassword) {
        User user = userRepository.findByUuid(userUuid)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.USER_PASSWORD_ERROR);
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        String newVersion = TokenVersionUtils.incrementVersion(user.getTokenVersion());
        user.setTokenVersion(newVersion);
        userRepository.save(user);

        sessionRevoker.revokeAllSessions(user.getUuid());
    }

    /**
     * 刪除帳號
     *
     * <p>驗證密碼正確後將帳號狀態設為 DELETED，並清除 Redis 中的所有快取，
     * 使所有現有 Token 立即失效。</p>
     *
     * @param userUuid 用戶 UUID
     * @param password 當前密碼（明文），用於二次確認身份
     */
    @Transactional
    public void deleteAccount(UUID userUuid, String password) {
        User user = userRepository.findByUuid(userUuid)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.USER_PASSWORD_ERROR);
        }

        user.setStatus(UserStatus.DELETED);
        userRepository.save(user);

        sessionRevoker.revokeAllSessions(user.getUuid());
    }
}
