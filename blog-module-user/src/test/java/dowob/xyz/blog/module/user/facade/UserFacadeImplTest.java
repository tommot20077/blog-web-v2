package dowob.xyz.blog.module.user.facade;

import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.user.mapper.UserMapper;
import dowob.xyz.blog.module.user.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserFacadeImpl 單元測試
 *
 * <p>
 * 驗證 {@link UserFacadeImpl} 唯一的跨模組查詢方法 {@code getDisplayInfoByUuids}：
 * 去重、切批、忽略 null、空輸入不發查詢、查無的 uuid 不出現在結果中。
 * M8 起 Long 版方法（{@code getUserUuidById} 等）已移除，其測試一併移除。
 * </p>
 *
 * @author Yuan
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
class UserFacadeImplTest {

    /** Mock：用戶 MyBatis 查詢（批次顯示投影） */
    @Mock
    private UserMapper userMapper;

    /** 受測物件 */
    @InjectMocks
    private UserFacadeImpl userFacadeImpl;

    /* =========================================================================
       getDisplayInfoByUuids 測試（comment 模組以 uuid 批次解析留言作者，取代 JOIN users）
       ========================================================================= */

    @Test
    @DisplayName("getDisplayInfoByUuids → 回傳以使用者 uuid 為 key 的顯示投影（uuid ＋ nickname ＋ avatarUrl）")
    void getDisplayInfoByUuids_existingUsers_returnsMapKeyedByUuid() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        when(userMapper.findDisplayInfoByUuids(anyList()))
                .thenReturn(List.of(displayRow(uuid1, "Alice", "https://img/a.png"), displayRow(uuid2, "Bob", null)));

        Map<UUID, UserDisplayInfo> result = userFacadeImpl.getDisplayInfoByUuids(List.of(uuid1, uuid2));

        assertThat(result).containsOnlyKeys(uuid1, uuid2);
        assertThat(result.get(uuid1)).isEqualTo(new UserDisplayInfo(uuid1, "Alice", "https://img/a.png"));
        assertThat(result.get(uuid2)).isEqualTo(new UserDisplayInfo(uuid2, "Bob", null));
    }

    @Test
    @DisplayName("getDisplayInfoByUuids → 同一 uuid 重複出現時只查一次，並忽略 null 元素")
    void getDisplayInfoByUuids_duplicatesAndNulls_queriesEachUuidOnce() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<List<UUID>> observedBatches = new ArrayList<>();
        when(userMapper.findDisplayInfoByUuids(anyList())).thenAnswer(invocation -> {
            observedBatches.add(List.copyOf(invocation.getArgument(0)));
            return List.<User>of();
        });

        userFacadeImpl.getDisplayInfoByUuids(Arrays.asList(a, a, null, b, a));

        assertThat(observedBatches).hasSize(1);
        assertThat(observedBatches.get(0)).containsExactlyInAnyOrder(a, b);
    }

    @Test
    @DisplayName("getDisplayInfoByUuids → 相異 uuid 數超過批次上限時切批")
    void getDisplayInfoByUuids_moreThanBatchSize_splitsIntoBatches() {
        List<Integer> observedBatchSizes = new ArrayList<>();
        when(userMapper.findDisplayInfoByUuids(anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(0);
            observedBatchSizes.add(batch.size());
            return List.<User>of();
        });
        List<UUID> uuids = java.util.stream.IntStream.range(0, BatchedQuery.BATCH_SIZE + 100)
                .mapToObj(i -> UUID.randomUUID()).toList();

        userFacadeImpl.getDisplayInfoByUuids(uuids);

        assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
    }

    @Test
    @DisplayName("getDisplayInfoByUuids → null、空集合、全為 null 皆回傳空 Map 且不發出 IN () 查詢")
    void getDisplayInfoByUuids_nullOrEmpty_returnsEmptyWithoutQuery() {
        assertThat(userFacadeImpl.getDisplayInfoByUuids(null)).isEmpty();
        assertThat(userFacadeImpl.getDisplayInfoByUuids(List.of())).isEmpty();
        assertThat(userFacadeImpl.getDisplayInfoByUuids(Arrays.asList(null, null))).isEmpty();

        verify(userMapper, never()).findDisplayInfoByUuids(anyList());
    }

    @Test
    @DisplayName("getDisplayInfoByUuids → 查無此使用者時該 uuid 不出現在結果中")
    void getDisplayInfoByUuids_unknownUuid_isAbsentFromResult() {
        UUID known = UUID.randomUUID();
        when(userMapper.findDisplayInfoByUuids(anyList()))
                .thenReturn(List.of(displayRow(known, "Alice", null)));

        Map<UUID, UserDisplayInfo> result = userFacadeImpl.getDisplayInfoByUuids(List.of(known, UUID.randomUUID()));

        assertThat(result).containsOnlyKeys(known);
    }

    /* =========================================================================
       測試輔助方法
       ========================================================================= */

    /**
     * 建立 mapper 回傳的顯示投影列（僅 uuid / nickname / avatarUrl 三欄有值）。
     *
     * @param uuid      使用者公開 UUID
     * @param nickname  暱稱
     * @param avatarUrl 頭像網址（可為 null）
     * @return 僅含投影欄位的 User
     */
    private User displayRow(UUID uuid, String nickname, String avatarUrl) {
        User user = new User();
        user.setUuid(uuid);
        user.setNickname(nickname);
        user.setAvatarUrl(avatarUrl);
        return user;
    }
}
