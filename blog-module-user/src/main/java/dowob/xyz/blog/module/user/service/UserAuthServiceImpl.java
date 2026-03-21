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
 * <p>實作 {@link UserAuthService} 介面，提供 JWT 認證過程中所需的
 * Token 版本號查詢與用戶簡要資訊查詢功能。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class UserAuthServiceImpl implements UserAuthService {

    /** 用戶資料存取 */
    private final UserRepository userRepository;

    /**
     * 取得用戶的 Token 版本號
     *
     * <p>用於 JWT 驗證時比對版本號，若版本不一致則視為 Token 已失效。</p>
     *
     * @param userId 用戶 ID
     * @return Token 版本號字串
     */
    @Override
    public String getUserTokenVersion(Long userId) {
        return userRepository.findById(userId)
                .map(User::getTokenVersion)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    /**
     * 取得用戶的簡要資訊
     *
     * <p>回傳包含 ID、信箱、角色與帳號可用狀態的 {@link UserAuthService.SimpleUserDetail}。</p>
     *
     * @param userId 用戶 ID
     * @return 用戶簡要資訊
     */
    @Override
    public UserAuthService.SimpleUserDetail getUserDetail(Long userId) {
        return userRepository.findById(userId)
                .map(u -> new UserAuthService.SimpleUserDetail(u.getId(), u.getEmail(), u.getRole().name(), u.getStatus().isAvailable()))
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }
}
