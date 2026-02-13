package dowob.xyz.blog.module.user.facade;

import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * UserFacade 實作
 *
 * <p>
 * 提供跨模組的用戶查詢能力，僅暴露其他模組所需的最小介面。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class UserFacadeImpl implements UserFacade {

    /**
     * 用戶 Repository
     */
    private final UserRepository userRepository;

    /**
     * 根據用戶內部 ID 取得對外公開的 UUID
     *
     * @param userId 用戶資料庫主鍵
     * @return 用戶 UUID，若不存在則回傳 empty
     */
    @Override
    public Optional<UUID> getUserUuidById(Long userId) {
        return userRepository.findById(userId)
                .map(user -> user.getUuid());
    }

    /**
     * 根據用戶內部 ID 取得暱稱
     *
     * @param userId 用戶資料庫主鍵
     * @return 用戶暱稱，若不存在則回傳 empty
     */
    @Override
    public Optional<String> getUserNicknameById(Long userId) {
        return userRepository.findById(userId)
                .map(user -> user.getNickname());
    }
}
