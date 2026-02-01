package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Infrastructure 用戶認證服務實作
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class UserAuthServiceImpl implements UserAuthService {

    private final UserRepository userRepository;

    @Override
    public String getUserTokenVersion(Long userId) {
        return userRepository.findById(userId)
                .map(User::getTokenVersion)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    @Override
    public UserAuthService.SimpleUserDetail getUserDetail(Long userId) {
        return userRepository.findById(userId)
                .map(u -> new UserAuthService.SimpleUserDetail(u.getId(), u.getEmail(), u.getRole().name(), u.getStatus().isAvailable()))
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }
}
