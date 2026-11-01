package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.UserStatus;

import dowob.xyz.blog.module.user.util.TokenVersionUtils;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.dto.response.UserProfileResponse;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
import dowob.xyz.blog.module.user.validation.ProfileFieldLimits;
import dowob.xyz.blog.module.user.validation.SafeHttpUrlValidator;
import dowob.xyz.blog.module.user.validation.SafeSocialLinksValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Predicate;

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

    /** 已刪除使用者的暱稱前綴（後接 uuid 片段以維持 {@code uq_users_nickname} 唯一） */
    private static final String DELETED_NICKNAME_PREFIX = "已刪除使用者#";

    /** 已刪除使用者的 email 墓碑網域：{@code .invalid} 為 RFC 2606 保留 TLD，永不可投遞 */
    private static final String DELETED_EMAIL_DOMAIN = "@invalid.invalid";

    /** 用戶資料存取 */
    private final UserRepository userRepository;

    /** 密碼加密器 */
    private final PasswordEncoder passwordEncoder;

    /** Session 撤銷服務，用於改密碼／刪帳號後撤銷所有既有 session */
    private final SessionRevoker sessionRevoker;

    /** 驗證 Token 存取，刪除帳號時清空該使用者的 token */
    private final VerificationTokenRepository verificationTokenRepository;

    /** 交易範本：刪除帳號的 DB 寫入收斂在交易內，commit 後才發送事件 */
    private final TransactionTemplate transactionTemplate;

    /** 使用者事件發布元件 */
    private final UserEventPublisher userEventPublisher;

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
     * 刪除帳號（匿名化，DATA-02）
     *
     * <p>驗證密碼正確後，在<b>同一個交易</b>內：把 {@code users} 列匿名化、將狀態設為 DELETED、遞增 tokenVersion、
     * 刪除該使用者的所有驗證 Token，並撤銷所有 session。交易 <b>commit 之後</b>才發送 {@code UserDeletedEvent}
     * （best-effort，見 {@link UserEventPublisher}），由其他模組清理各自持有的個人資料。</p>
     *
     * <p><b>為何匿名化而非只改狀態</b>：公開讀取路徑（文章作者、留言者顯示）經 {@code UserFacade.getDisplayInfoByUuids}
     * 讀取 {@code users} 列且<b>不依 status 過濾</b>；洗掉這一列的 PII 後，所有既有讀取路徑自動匿名，不必逐路徑改動。
     * 保留已發布文章與留言（Q2 定案）。</p>
     *
     * <p><b>匿名化內容</b>（皆受 {@code users} 的 UNIQUE 約束，不能全部設成同一字串）：</p>
     * <ul>
     *   <li>{@code nickname} → {@code 已刪除使用者#<uuid 前 8 碼>}：可讀、可區分不同的已刪除使用者；</li>
     *   <li>{@code username} → {@code deleted_<uuid>}、{@code email} → {@code deleted+<uuid>@invalid.invalid}
     *       （{@code .invalid} 為 RFC 2606 保留 TLD，永不可投遞）；</li>
     *   <li>頭貼、簡介、網站、社群連結、所在地 → {@code null}；信箱驗證旗標與 5 個通知偏好 → {@code false}；</li>
     *   <li>{@code password_hash} → 由隨機祕密產生、沒有人知道明文的 bcrypt 雜湊，任何舊登入路徑都不可能再通過。</li>
     * </ul>
     * <p>墓碑值若剛好被他人先註冊占用（卡位），依序退回較長的格式與隨機值，刪帳不會因 UNIQUE 約束失敗。</p>
     *
     * <p><b>Session 撤銷</b>：{@link SessionRevoker#revokeAllSessions} 在交易同步進行中會延後到
     * {@code afterCommit} 才清除 Redis（security.md §10）；交易 rollback 則完全不清除。</p>
     *
     * @param userUuid 用戶 UUID
     * @param password 當前密碼（明文），用於二次確認身份
     */
    public void deleteAccount(UUID userUuid, String password) {
        User user = userRepository.findByUuid(userUuid)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.USER_PASSWORD_ERROR);
        }

        /* bcrypt 雜湊耗時，放在交易之外計算，不佔用資料庫連線 */
        String unusablePasswordHash = passwordEncoder.encode(UUID.randomUUID() + UUID.randomUUID().toString());

        transactionTemplate.executeWithoutResult(status -> {
            anonymize(user, unusablePasswordHash);
            userRepository.save(user);
            verificationTokenRepository.deleteByUserId(user.getId());
            sessionRevoker.revokeAllSessions(user.getUuid());
        });

        /* 匿名化已 commit，才通知其他模組連動清理（best-effort） */
        userEventPublisher.publishDeleted(user.getUuid());
    }

    /**
     * 就地匿名化使用者列（不儲存，由呼叫端 save）。
     *
     * @param user                 要匿名化的使用者
     * @param unusablePasswordHash 無人知道明文的密碼雜湊
     */
    private void anonymize(User user, String unusablePasswordHash) {
        UUID uuid = user.getUuid();
        UUID randomSeed = UUID.randomUUID();

        user.setNickname(firstUnused(userRepository::existsByNickname,
                DELETED_NICKNAME_PREFIX + uuid.toString().substring(0, 8),
                DELETED_NICKNAME_PREFIX + uuid.toString().replace("-", ""),
                DELETED_NICKNAME_PREFIX + randomSeed.toString().replace("-", "")));
        user.setUsername(firstUnused(userRepository::existsByUsername,
                "deleted_" + uuid, "deleted_" + randomSeed));
        user.setEmail(firstUnused(userRepository::existsByEmail,
                "deleted+" + uuid + DELETED_EMAIL_DOMAIN, "deleted+" + randomSeed + DELETED_EMAIL_DOMAIN));

        user.setAvatarUrl(null);
        user.setBio(null);
        user.setWebsite(null);
        user.setSocialLinks(null);
        user.setLocation(null);
        user.setEmailVerified(false);
        user.setNotificationComment(false);
        user.setNotificationLike(false);
        user.setNotificationReview(false);
        user.setNotificationFollow(false);
        user.setNotificationNewsletter(false);

        user.setPasswordHash(unusablePasswordHash);
        user.setTokenVersion(TokenVersionUtils.incrementVersion(user.getTokenVersion()));
        user.setStatus(UserStatus.DELETED);
    }

    /**
     * 依序挑出第一個尚未被占用的候選值；全部都被占用時回傳最後一個，交由資料庫 UNIQUE 約束把關。
     *
     * @param taken      判斷候選值是否已被占用
     * @param candidates 候選值，依優先順序排列，至少一個
     * @return 第一個未被占用的候選值
     */
    private static String firstUnused(Predicate<String> taken, String... candidates) {
        for (String candidate : candidates) {
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        return candidates[candidates.length - 1];
    }
}
