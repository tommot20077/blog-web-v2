package dowob.xyz.blog.module.user.facade;

import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.AuthorInfo;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.user.mapper.UserMapper;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
     * 用戶 MyBatis Mapper（批次作者投影查詢）
     */
    private final UserMapper userMapper;

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

    /**
     * 根據用戶內部 ID 取得帳號名稱
     *
     * @param userId 用戶資料庫主鍵
     * @return 用戶帳號，若不存在則回傳 empty
     */
    @Override
    public Optional<String> getUserUsernameById(Long userId) {
        return userRepository.findById(userId)
                .map(user -> user.getUsername());
    }

    /**
     * 批次取得作者投影（uuid ＋ nickname）
     *
     * <p>先去重再查：一頁文章常出自同一作者，不去重會讓 {@code IN} 清單長度等於頁大小。
     * 去重後的輸入上界仍是分頁 size 上限（{@code PageQuery.MAX_SIZE}），大於
     * {@link BatchedQuery#BATCH_SIZE}，故經由 {@link BatchedQuery} 切批；
     * 本查詢以主鍵 {@code IN} 取列、結果收成 Map，屬跨批可合併形狀。</p>
     *
     * @param userIds 使用者資料庫主鍵集合
     * @return 使用者主鍵 → 作者投影；查無的 id 不出現在結果中
     */
    @Override
    public Map<Long, AuthorInfo> getAuthorInfoByIds(Collection<Long> userIds) {
        if (userIds == null) {
            return Map.of();
        }
        Set<Long> distinctIds = userIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return BatchedQuery.queryInBatches(distinctIds, userMapper::findAuthorInfoByIds).stream()
                .collect(Collectors.toMap(User::getId, user -> new AuthorInfo(user.getUuid(), user.getNickname())));
    }
}
