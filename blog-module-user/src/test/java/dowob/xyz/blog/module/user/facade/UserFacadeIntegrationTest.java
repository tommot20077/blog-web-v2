package dowob.xyz.blog.module.user.facade;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.service.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserFacade 批次顯示投影整合測試（PERF-02；M8 起只剩 UUID 版）
 *
 * <p>單元測試以 mock mapper 驗證去重與切批的接線；本測試驗證 mock 看不到的部分：
 * {@code <foreach>} 展開的 SQL 在真實 PostgreSQL 上可執行、{@code uuid} 欄位經
 * {@code UUIDTypeHandler} 正確映射、且投影確實不含 {@code password_hash}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class UserFacadeIntegrationTest extends AbstractIntegrationTest {

    /** 受測 facade */
    @Autowired
    private UserFacade userFacade;

    /** 建立與清理測試使用者 */
    @Autowired
    private UserRepository userRepository;

    /** 本測試建立的使用者，供 {@link #cleanUp()} 刪除 */
    private final List<User> createdUsers = new ArrayList<>();

    /**
     * 刪除本測試建立的使用者，確保測試隔離。
     */
    @AfterEach
    void cleanUp() {
        userRepository.deleteAll(createdUsers);
        createdUsers.clear();
    }

    @Test
    @DisplayName("getDisplayInfoByUuids → 真實 SQL 回傳正確的 uuid、nickname、avatarUrl，重複與查無的 uuid 不影響結果")
    void getDisplayInfoByUuids_realDatabase_returnsProjectionKeyedByUuid() {
        User alice = createUser("alice-display", "Alice");
        alice.setAvatarUrl("https://img.example.com/alice.png");
        userRepository.save(alice);
        User bob = createUser("bob-display", "Bob");

        Map<UUID, UserDisplayInfo> result = userFacade.getDisplayInfoByUuids(
                List.of(alice.getUuid(), bob.getUuid(), alice.getUuid(), UUID.randomUUID()));

        assertThat(result).containsOnlyKeys(alice.getUuid(), bob.getUuid());
        assertThat(result.get(alice.getUuid()))
                .isEqualTo(new UserDisplayInfo(alice.getUuid(), "Alice", "https://img.example.com/alice.png"));
        assertThat(result.get(bob.getUuid()))
                .isEqualTo(new UserDisplayInfo(bob.getUuid(), "Bob", null));
    }

    @Test
    @DisplayName("getDisplayInfoByUuids → 不依 status 過濾：停權與軟刪除使用者仍回傳暱稱與頭像（與先前 LEFT JOIN users 行為相同）")
    void getDisplayInfoByUuids_bannedAndDeletedUsers_areStillReturned() {
        User banned = createUser("banned-display", "Banned");
        banned.setStatus(UserStatus.BANNED);
        banned.setAvatarUrl("https://img.example.com/banned.png");
        userRepository.save(banned);
        User deleted = createUser("deleted-display", "Deleted");
        deleted.setStatus(UserStatus.DELETED);
        userRepository.save(deleted);

        Map<UUID, UserDisplayInfo> result = userFacade.getDisplayInfoByUuids(
                List.of(banned.getUuid(), deleted.getUuid()));

        assertThat(result).containsOnlyKeys(banned.getUuid(), deleted.getUuid());
        assertThat(result.get(banned.getUuid()).nickname()).isEqualTo("Banned");
        assertThat(result.get(banned.getUuid()).avatarUrl()).isEqualTo("https://img.example.com/banned.png");
        assertThat(result.get(deleted.getUuid()).nickname()).isEqualTo("Deleted");
    }

    @Test
    @DisplayName("getDisplayInfoByUuids → 超過批次上限的 uuid 仍能完整查回（切批後結果不遺漏）")
    void getDisplayInfoByUuids_moreThanBatchSize_returnsAllExistingUsers() {
        User alice = createUser("alice-batch-display", "Alice");
        User bob = createUser("bob-batch-display", "Bob");
        List<UUID> input = new ArrayList<>();
        for (int i = 0; i < BatchedQuery.BATCH_SIZE + 50; i++) {
            input.add(UUID.randomUUID());
        }
        input.set(0, alice.getUuid());
        input.set(input.size() - 1, bob.getUuid());

        Map<UUID, UserDisplayInfo> result = userFacade.getDisplayInfoByUuids(input);

        assertThat(result).containsOnlyKeys(alice.getUuid(), bob.getUuid());
    }

    /**
     * 建立並持久化一位測試使用者。
     *
     * @param username 帳號（亦用於組成唯一 email）
     * @param nickname 暱稱
     * @return 已持久化的使用者（含資料庫主鍵）
     */
    private User createUser(String username, String nickname) {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setEmail(username + "@example.com");
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPasswordHash("not-a-real-hash");
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion("v1");
        User saved = userRepository.save(user);
        createdUsers.add(saved);
        return saved;
    }
}
