package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    /** Redis 操作模板，用於清除快取 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 更新個人資料
     *
     * <p>更新暱稱與個人簡介；若新暱稱已被其他用戶使用，拋出 BusinessException。</p>
     *
     * @param userId   用戶 ID
     * @param nickname 新的暱稱
     * @param bio      新的個人簡介（可為 null）
     */
    @Transactional
    public void updateProfile(Long userId, String nickname, String bio) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (!user.getNickname().equals(nickname) && userRepository.existsByNickname(nickname)) {
            throw new BusinessException(UserErrorCode.NICKNAME_DUPLICATED);
        }

        user.setNickname(nickname);
        user.setBio(bio);
        userRepository.save(user);
    }

    /**
     * 修改密碼
     *
     * <p>驗證舊密碼正確後更新密碼雜湊，並遞增 tokenVersion 使所有現有 Token 失效。
     * 同步更新 Redis Auth Hash 中的版本號。</p>
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
        String newVersion = AuthService.incrementVersion(user.getTokenVersion());
        user.setTokenVersion(newVersion);
        userRepository.save(user);

        String redisKey = RedisKeyConstant.getUserAuthKey(userId);
        redisTemplate.opsForHash().put(redisKey, RedisKeyConstant.FIELD_VERSION, newVersion);
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

        redisTemplate.delete(RedisKeyConstant.getUserAuthKey(userId));
        redisTemplate.delete(RedisKeyConstant.getUserRefreshKey(userId));
    }
}
