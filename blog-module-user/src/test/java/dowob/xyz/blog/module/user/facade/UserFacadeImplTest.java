package dowob.xyz.blog.module.user.facade;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.infrastructure.facade.dto.AuthorInfo;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.user.mapper.UserMapper;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.repository.UserRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserFacadeImpl 單元測試
 *
 * <p>
 * 驗證 {@link UserFacadeImpl} 跨模組查詢方法的行為：
 * {@code getUserUuidById} 與 {@code getUserNicknameById}。
 * 測試涵蓋用戶存在（回傳正確值）與不存在（回傳 Optional.empty()）兩種情境。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
class UserFacadeImplTest {

    /** Mock：用戶資料存取 */
    @Mock
    private UserRepository userRepository;

    /** Mock：用戶 MyBatis 查詢（批次作者投影） */
    @Mock
    private UserMapper userMapper;

    /** 受測物件 */
    @InjectMocks
    private UserFacadeImpl userFacadeImpl;

    /** 測試用用戶 ID */
    private static final Long TEST_USER_ID = 1L;

    /* =========================================================================
       getUserUuidById 測試
       ========================================================================= */

    /**
     * 驗證：用戶存在時應回傳含 UUID 的 Optional。
     */
    @Test
    @DisplayName("getUserUuidById → 用戶存在 → 應回傳 Optional<UUID>")
    void getUserUuidById_userExists_shouldReturnUuid() {
        UUID expectedUuid = UUID.randomUUID();
        User mockUser = buildUser(expectedUuid, "testUser");
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));

        Optional<UUID> result = userFacadeImpl.getUserUuidById(TEST_USER_ID);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(expectedUuid);
    }

    /**
     * 驗證：用戶不存在時應回傳 Optional.empty()。
     */
    @Test
    @DisplayName("getUserUuidById → 用戶不存在 → 應回傳 Optional.empty()")
    void getUserUuidById_userNotFound_shouldReturnEmpty() {
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        Optional<UUID> result = userFacadeImpl.getUserUuidById(TEST_USER_ID);

        assertThat(result).isEmpty();
    }

    /* =========================================================================
       getUserNicknameById 測試
       ========================================================================= */

    /**
     * 驗證：用戶存在時應回傳含暱稱的 Optional。
     */
    @Test
    @DisplayName("getUserNicknameById → 用戶存在 → 應回傳 Optional<String> 含暱稱")
    void getUserNicknameById_userExists_shouldReturnNickname() {
        User mockUser = buildUser(UUID.randomUUID(), "myNickname");
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));

        Optional<String> result = userFacadeImpl.getUserNicknameById(TEST_USER_ID);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("myNickname");
    }

    /**
     * 驗證：用戶不存在時應回傳 Optional.empty()。
     */
    @Test
    @DisplayName("getUserNicknameById → 用戶不存在 → 應回傳 Optional.empty()")
    void getUserNicknameById_userNotFound_shouldReturnEmpty() {
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        Optional<String> result = userFacadeImpl.getUserNicknameById(TEST_USER_ID);

        assertThat(result).isEmpty();
    }

    /* =========================================================================
       getAuthorInfoByIds 測試（PERF-02：文章列表作者解析 N+1）
       ========================================================================= */

    @Test
    @DisplayName("getAuthorInfoByIds → 回傳以使用者主鍵為 key 的作者投影（uuid ＋ nickname）")
    void getAuthorInfoByIds_existingUsers_returnsMapKeyedById() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        when(userMapper.findAuthorInfoByIds(anyList()))
                .thenReturn(List.of(authorRow(1L, uuid1, "Alice"), authorRow(2L, uuid2, "Bob")));

        Map<Long, AuthorInfo> result = userFacadeImpl.getAuthorInfoByIds(List.of(1L, 2L));

        assertThat(result).containsOnlyKeys(1L, 2L);
        assertThat(result.get(1L)).isEqualTo(new AuthorInfo(uuid1, "Alice"));
        assertThat(result.get(2L)).isEqualTo(new AuthorInfo(uuid2, "Bob"));
    }

    @Test
    @DisplayName("getAuthorInfoByIds → 同一作者重複出現時只查一次（一頁文章常為同一作者）")
    void getAuthorInfoByIds_duplicateIds_queriesEachIdOnce() {
        List<List<Long>> observedBatches = new ArrayList<>();
        when(userMapper.findAuthorInfoByIds(anyList())).thenAnswer(invocation -> {
            observedBatches.add(List.copyOf(invocation.getArgument(0)));
            return List.<User>of();
        });

        userFacadeImpl.getAuthorInfoByIds(Arrays.asList(7L, 7L, 7L, 8L, 7L));

        assertThat(observedBatches).hasSize(1);
        assertThat(observedBatches.get(0)).containsExactlyInAnyOrder(7L, 8L);
    }

    @Test
    @DisplayName("getAuthorInfoByIds → 相異作者數超過批次上限時切批（輸入上界為分頁 size 上限 1000）")
    void getAuthorInfoByIds_moreThanBatchSize_splitsIntoBatches() {
        List<Integer> observedBatchSizes = new ArrayList<>();
        when(userMapper.findAuthorInfoByIds(anyList())).thenAnswer(invocation -> {
            List<Long> batch = invocation.getArgument(0);
            observedBatchSizes.add(batch.size());
            return List.<User>of();
        });
        List<Long> ids = LongStream.rangeClosed(1, BatchedQuery.BATCH_SIZE + 100).boxed().toList();

        userFacadeImpl.getAuthorInfoByIds(ids);

        assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
    }

    @Test
    @DisplayName("getAuthorInfoByIds → null 或空集合回傳空 Map，且不發出 IN () 查詢")
    void getAuthorInfoByIds_nullOrEmpty_returnsEmptyWithoutQuery() {
        assertThat(userFacadeImpl.getAuthorInfoByIds(null)).isEmpty();
        assertThat(userFacadeImpl.getAuthorInfoByIds(List.of())).isEmpty();

        verify(userMapper, never()).findAuthorInfoByIds(anyList());
    }

    @Test
    @DisplayName("getAuthorInfoByIds → 輸入中的 null 被忽略，不送進 IN 查詢")
    void getAuthorInfoByIds_nullElement_isIgnored() {
        List<List<Long>> observedBatches = new ArrayList<>();
        when(userMapper.findAuthorInfoByIds(anyList())).thenAnswer(invocation -> {
            observedBatches.add(List.copyOf(invocation.getArgument(0)));
            return List.<User>of();
        });

        userFacadeImpl.getAuthorInfoByIds(Arrays.asList(3L, null));

        assertThat(observedBatches).containsExactly(List.of(3L));
    }

    @Test
    @DisplayName("getAuthorInfoByIds → 查無此使用者時該 id 不出現在結果中（呼叫端自行決定缺值呈現）")
    void getAuthorInfoByIds_unknownId_isAbsentFromResult() {
        when(userMapper.findAuthorInfoByIds(anyList()))
                .thenReturn(List.of(authorRow(1L, UUID.randomUUID(), "Alice")));

        Map<Long, AuthorInfo> result = userFacadeImpl.getAuthorInfoByIds(List.of(1L, 999L));

        assertThat(result).containsOnlyKeys(1L);
    }

    /* =========================================================================
       測試輔助方法
       ========================================================================= */

    /**
     * 建立 mapper 回傳的作者投影列（僅 id / uuid / nickname 三欄有值）。
     *
     * @param id       使用者主鍵
     * @param uuid     使用者公開 UUID
     * @param nickname 暱稱
     * @return 僅含投影欄位的 User
     */
    private User authorRow(Long id, UUID uuid, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUuid(uuid);
        user.setNickname(nickname);
        return user;
    }

    /**
     * 建立測試用 User 物件。
     *
     * @param uuid     對外公開的 UUID
     * @param nickname 暱稱
     * @return User 實體
     */
    private User buildUser(UUID uuid, String nickname) {
        User user = new User();
        user.setId(TEST_USER_ID);
        user.setUuid(uuid);
        user.setEmail("test@example.com");
        user.setNickname(nickname);
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion("v1");
        return user;
    }
}
