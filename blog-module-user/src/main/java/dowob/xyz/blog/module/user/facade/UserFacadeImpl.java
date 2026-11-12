package dowob.xyz.blog.module.user.facade;

import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.user.mapper.UserMapper;
import dowob.xyz.blog.module.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
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
 * @version 2.0（M8：移除 Long 版方法，只剩 UUID 版）
 */
@Service
@RequiredArgsConstructor
public class UserFacadeImpl implements UserFacade {

    /**
     * 用戶 MyBatis Mapper（批次顯示投影查詢）
     */
    private final UserMapper userMapper;

    /**
     * 以使用者公開 UUID 批次取得顯示投影（uuid ＋ nickname ＋ avatarUrl）
     *
     * <p>先去重再查：一頁內常有重複使用者，不去重會讓 {@code IN} 清單長度等於頁大小；
     * 經 {@link BatchedQuery} 切批，結果收成 Map（跨批可合併）。<b>不依 status／軟刪除過濾</b>，停權或已刪除的使用者仍回傳。</p>
     *
     * @param userUuids 使用者公開 UUID 集合
     * @return 使用者 UUID → 顯示投影；查無的 UUID 不出現在結果中
     */
    @Override
    public Map<UUID, UserDisplayInfo> getDisplayInfoByUuids(Collection<UUID> userUuids) {
        if (userUuids == null) {
            return Map.of();
        }
        Set<UUID> distinctUuids = userUuids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return BatchedQuery.queryInBatches(distinctUuids, userMapper::findDisplayInfoByUuids).stream()
                .collect(Collectors.toMap(User::getUuid,
                        user -> new UserDisplayInfo(user.getUuid(), user.getNickname(), user.getAvatarUrl())));
    }
}
