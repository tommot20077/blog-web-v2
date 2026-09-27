package dowob.xyz.blog.module.user.facade;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.AuthorInfo;
import dowob.xyz.blog.module.user.mapper.UserMapper;
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
 * UserFacade 批次作者投影整合測試（PERF-02）
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

    /** 直接驗證投影欄位用 */
    @Autowired
    private UserMapper userMapper;

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
    @DisplayName("getAuthorInfoByIds → 真實 SQL 回傳正確的 uuid 與 nickname，重複 id 與查無的 id 皆不影響結果")
    void getAuthorInfoByIds_realDatabase_returnsProjectionKeyedById() {
        User alice = createUser("alice-perf02", "Alice");
        User bob = createUser("bob-perf02", "Bob");

        Map<Long, AuthorInfo> result = userFacade.getAuthorInfoByIds(
                List.of(alice.getId(), bob.getId(), alice.getId(), Long.MAX_VALUE));

        assertThat(result).containsOnlyKeys(alice.getId(), bob.getId());
        assertThat(result.get(alice.getId())).isEqualTo(new AuthorInfo(alice.getUuid(), "Alice"));
        assertThat(result.get(bob.getId())).isEqualTo(new AuthorInfo(bob.getUuid(), "Bob"));
    }

    @Test
    @DisplayName("findAuthorInfoByIds → 投影只含 id / uuid / nickname，不讀取 password_hash")
    void findAuthorInfoByIds_realDatabase_doesNotLoadPasswordHash() {
        User alice = createUser("alice-hash-perf02", "Alice");

        List<User> rows = userMapper.findAuthorInfoByIds(List.of(alice.getId()));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(alice.getId());
            assertThat(row.getUuid()).isEqualTo(alice.getUuid());
            assertThat(row.getNickname()).isEqualTo("Alice");
            assertThat(row.getPasswordHash()).isNull();
            assertThat(row.getEmail()).isNull();
        });
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
