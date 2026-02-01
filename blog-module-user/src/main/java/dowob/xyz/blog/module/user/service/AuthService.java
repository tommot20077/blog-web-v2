package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.security.JwtUtil;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 認證業務服務
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    /**
     * 用戶註冊
     *
     * @param email    信箱
     * @param password 密碼
     * @param nickname 暱稱
     */
    @Transactional
    public void register(String email, String password, String nickname) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_DUPLICATED);
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(UserErrorCode.NICKNAME_DUPLICATED);
        }

        User user = new User();
        user.setEmail(email);
        user.setNickname(nickname);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(Role.USER);
        user.setStatus(UserStatus.PENDING_VERIFICATION);

        userRepository.save(user);

        // TODO: 發送驗證信
    }

    /**
     * 用戶登入
     *
     * @param email    信箱
     * @param password 密碼
     * @return JWT Token
     */
    public String login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_PASSWORD_ERROR));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.USER_PASSWORD_ERROR);
        }

        if (!user.getStatus().isAvailable()) {
            throw new BusinessException(UserErrorCode.ACCOUNT_SUSPENDED);
        }

        // 生成 Token
        String token = jwtUtil.generateToken(user.getId(), user.getRole().name(), user.getTokenVersion());

        // 更新 Redis 快取 (Hash 結構: v=版本號, status=狀態)
        // 更新 Redis 快取 (Hash 結構: v=版本號, status=狀態)
        String redisKey = RedisKeyConstant.getUserAuthKey(user.getId());
        redisTemplate.opsForHash().put(redisKey, RedisKeyConstant.FIELD_VERSION, user.getTokenVersion());
        redisTemplate.opsForHash().put(redisKey, RedisKeyConstant.FIELD_STATUS, user.getStatus().name());

        return token;
    }
}
