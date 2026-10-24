package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.infrastructure.security.AuthState;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure 用戶認證服務實作
 *
 * <p>實作 {@link UserAuthService} 介面，提供 JWT 認證過程中所需的
 * 認證狀態查詢功能。</p>
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
     * 以 uuid 查詢使用者的認證狀態
     *
     * <p>回傳包含 id、uuid、status、role、tokenVersion 的 {@link AuthState}；
     * 狀態不做任何過濾，是否放行由呼叫端決定。{@code users.token_version} 等欄位在資料庫中可為 NULL，
     * 任一認證欄位為 null 時不建出缺欄位的 AuthState，改回傳 empty（呼叫端視為查無使用者）。</p>
     *
     * @param uuid 使用者 UUID
     * @return 認證狀態；使用者不存在時為 empty
     */
    @Override
    public Optional<AuthState> findAuthStateByUuid(UUID uuid) {
        return userRepository.findByUuid(uuid)
                .filter(u -> u.getTokenVersion() != null && u.getStatus() != null && u.getRole() != null)
                .map(u -> new AuthState(u.getId(), u.getUuid(), u.getStatus(), u.getRole(), u.getTokenVersion()));
    }
}
