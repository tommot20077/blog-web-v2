package dowob.xyz.blog.module.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.HttpStatusBusinessException;
import dowob.xyz.blog.infrastructure.facade.dto.AuthorArticleStats;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.dto.response.AuthorSummaryResponse;
import dowob.xyz.blog.module.user.model.dto.response.PublicProfileResponse;
import dowob.xyz.blog.module.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 公開作者功能整合測試（真實 PostgreSQL；article 模組以 {@code ArticleFacade} mock 提供統計）
 *
 * <p>驗證 mock 看不到的部分：{@code findActiveAuthorsByUuids} 的真實 SQL——只列 ACTIVE、
 * ILIKE 不分大小寫且 {@code % _ \} 當字面、{@code <foreach>} 切批；以及公開資料的 JSON 欄位白名單。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class PublicAuthorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PublicAuthorService publicAuthorService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /** 本測試建立的使用者，供 {@link #cleanUp()} 刪除 */
    private final List<User> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll(createdUsers);
        createdUsers.clear();
    }

    private User createUser(String nickname, UserStatus status) {
        User user = newUser(nickname, status);
        User saved = userRepository.save(user);
        createdUsers.add(saved);
        return saved;
    }

    private User newUser(String nickname, UserStatus status) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setEmail("pa-" + suffix + "@example.com");
        user.setUsername("login-" + suffix);
        user.setNickname(nickname);
        user.setPasswordHash("not-a-real-hash");
        user.setRole(Role.USER);
        user.setStatus(status);
        user.setTokenVersion("v1");
        return user;
    }

    /** 讓 ArticleFacade 回傳指定作者的已發布篇數 */
    private void givenPublishedCounts(Map<UUID, Long> counts) {
        when(articleFacade.countPublishedByAuthor()).thenReturn(new HashMap<>(counts));
    }

    @Test
    @DisplayName("profile：ACTIVE 使用者 → 回傳公開欄位與統計；JSON 欄位集合為白名單，不含 email／username／role")
    void getPublicProfile_activeUser_returnsWhitelistedJson() throws Exception {
        User alice = newUser("it-profile-alice", UserStatus.ACTIVE);
        alice.setBio("hello");
        alice.setLocation("Taipei");
        alice.setWebsite("https://alice.example.com");
        alice.setSocialLinks("{\"github\":\"alice\",\"twitter\":\"@alice\"}");
        alice.setAvatarUrl("https://img.example.com/alice.png");
        alice = userRepository.save(alice);
        createdUsers.add(alice);
        when(articleFacade.getPublishedStatsByAuthor(alice.getUuid())).thenReturn(new AuthorArticleStats(2L, 300L, 9L));

        PublicProfileResponse response = publicAuthorService.getPublicProfile(alice.getUuid());

        assertThat(response.uuid()).isEqualTo(alice.getUuid());
        assertThat(response.nickname()).isEqualTo("it-profile-alice");
        assertThat(response.bio()).isEqualTo("hello");
        assertThat(response.location()).isEqualTo("Taipei");
        assertThat(response.website()).isEqualTo("https://alice.example.com");
        assertThat(response.socialLinks()).containsEntry("github", "alice").containsEntry("twitter", "@alice");
        assertThat(response.joinedAt()).isNotNull();
        assertThat(response.stats()).isEqualTo(new PublicProfileResponse.Stats(2L, 300L, 9L));

        String json = objectMapper.writeValueAsString(response);
        JsonNode node = objectMapper.readTree(json);
        assertThat(StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(node.fieldNames(), 0), false)
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("uuid", "nickname", "avatarUrl", "bio", "location", "website",
                        "socialLinks", "joinedAt", "stats");
        assertThat(json).doesNotContain("@example.com").doesNotContain("login-").doesNotContain("USER");
    }

    @Test
    @DisplayName("profile：不存在、DELETED、PENDING_VERIFICATION、BANNED → 同一個 404 A0101，訊息完全相同")
    void getPublicProfile_nonActiveOrMissing_areIndistinguishable() {
        User deleted = createUser("it-profile-deleted", UserStatus.DELETED);
        User pending = createUser("it-profile-pending", UserStatus.PENDING_VERIFICATION);
        User banned = createUser("it-profile-banned", UserStatus.BANNED);

        List<HttpStatusBusinessException> thrown = new ArrayList<>();
        for (UUID uuid : List.of(UUID.randomUUID(), deleted.getUuid(), pending.getUuid(), banned.getUuid())) {
            assertThatThrownBy(() -> publicAuthorService.getPublicProfile(uuid))
                    .isInstanceOf(HttpStatusBusinessException.class)
                    .satisfies(e -> thrown.add((HttpStatusBusinessException) e));
        }

        assertThat(thrown).hasSize(4).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.USER_NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo(thrown.get(0).getMessage());
        });
    }

    @Test
    @DisplayName("authors：只列 ACTIVE；DELETED／BANNED／PENDING_VERIFICATION 即使有已發布篇數也不出現")
    void searchAuthors_listsOnlyActiveUsers() {
        User active = createUser("it-status-active", UserStatus.ACTIVE);
        User deleted = createUser("it-status-deleted", UserStatus.DELETED);
        User banned = createUser("it-status-banned", UserStatus.BANNED);
        User pending = createUser("it-status-pending", UserStatus.PENDING_VERIFICATION);
        givenPublishedCounts(Map.of(active.getUuid(), 1L, deleted.getUuid(), 9L, banned.getUuid(), 9L,
                pending.getUuid(), 9L));

        assertThat(publicAuthorService.searchAuthors("it-status"))
                .extracting(AuthorSummaryResponse::uuid).containsExactly(active.getUuid());
    }

    @Test
    @DisplayName("authors：nickname 不分大小寫、任意位置比對；前後空白去除；avatarUrl 與篇數映射正確")
    void searchAuthors_keywordIsCaseInsensitiveSubstringAndTrimmed() {
        User alice = newUser("it-match-AliceWonder", UserStatus.ACTIVE);
        alice.setAvatarUrl("https://img.example.com/alice.png");
        alice = userRepository.save(alice);
        createdUsers.add(alice);
        User bob = createUser("it-match-bob", UserStatus.ACTIVE);
        givenPublishedCounts(Map.of(alice.getUuid(), 4L, bob.getUuid(), 2L));

        List<AuthorSummaryResponse> result = publicAuthorService.searchAuthors("  ALICEwon  ");

        assertThat(result).containsExactly(
                new AuthorSummaryResponse(alice.getUuid(), "it-match-AliceWonder", "https://img.example.com/alice.png", 4L));
    }

    @Test
    @DisplayName("authors：keyword 的 _ 是字面底線，不是萬用字元（不會匹配所有暱稱）")
    void searchAuthors_underscoreIsLiteral() {
        User plain = createUser("it-us-ab", UserStatus.ACTIVE);
        User underscore = createUser("it-us-a_b", UserStatus.ACTIVE);
        givenPublishedCounts(Map.of(plain.getUuid(), 1L, underscore.getUuid(), 1L));

        assertThat(publicAuthorService.searchAuthors("_")).extracting(AuthorSummaryResponse::uuid)
                .containsExactly(underscore.getUuid());
        assertThat(publicAuthorService.searchAuthors("a_b")).extracting(AuthorSummaryResponse::uuid)
                .containsExactly(underscore.getUuid());
    }

    @Test
    @DisplayName("authors：keyword 的 % 是字面百分號，不是萬用字元")
    void searchAuthors_percentIsLiteral() {
        User plain = createUser("it-pct-abc", UserStatus.ACTIVE);
        User percent = createUser("it-pct-100%", UserStatus.ACTIVE);
        givenPublishedCounts(Map.of(plain.getUuid(), 1L, percent.getUuid(), 1L));

        assertThat(publicAuthorService.searchAuthors("%")).extracting(AuthorSummaryResponse::uuid)
                .containsExactly(percent.getUuid());
        assertThat(publicAuthorService.searchAuthors("100%")).extracting(AuthorSummaryResponse::uuid)
                .containsExactly(percent.getUuid());
    }

    @Test
    @DisplayName("authors：keyword 的反斜線是字面字元，不會吃掉後面的字元或讓 SQL 出錯")
    void searchAuthors_backslashIsLiteral() {
        User plain = createUser("it-bs-ab", UserStatus.ACTIVE);
        User backslash = createUser("it-bs-a\\b", UserStatus.ACTIVE);
        givenPublishedCounts(Map.of(plain.getUuid(), 1L, backslash.getUuid(), 1L));

        assertThat(publicAuthorService.searchAuthors("a\\b")).extracting(AuthorSummaryResponse::uuid)
                .containsExactly(backslash.getUuid());
        assertThat(publicAuthorService.searchAuthors("\\")).extracting(AuthorSummaryResponse::uuid)
                .containsExactly(backslash.getUuid());
    }

    @Test
    @DisplayName("authors：排序為篇數 DESC → 暱稱 ASC（篇數相同者依暱稱字典序）")
    void searchAuthors_ordersByCountThenNickname() {
        User top = createUser("it-sort-zzz", UserStatus.ACTIVE);
        User tieAlpha = createUser("it-sort-alpha", UserStatus.ACTIVE);
        User tieBeta = createUser("it-sort-beta", UserStatus.ACTIVE);
        User tieGamma = createUser("it-sort-gamma", UserStatus.ACTIVE);
        User last = createUser("it-sort-aaa", UserStatus.ACTIVE);
        givenPublishedCounts(Map.of(top.getUuid(), 10L, tieGamma.getUuid(), 5L, tieAlpha.getUuid(), 5L,
                tieBeta.getUuid(), 5L, last.getUuid(), 1L));

        List<AuthorSummaryResponse> result = publicAuthorService.searchAuthors("it-sort");

        assertThat(result).extracting(AuthorSummaryResponse::nickname)
                .containsExactly("it-sort-zzz", "it-sort-alpha", "it-sort-beta", "it-sort-gamma", "it-sort-aaa");
    }

    @Test
    @DisplayName("authors：篇數相同時順序確定，重複呼叫結果一致")
    void searchAuthors_equalCounts_giveStableOrder() {
        /* uq_users_nickname 使暱稱唯一，無法在 DB 造出「同暱稱同篇數」；uuid 次要鍵的比較由 PublicAuthorServiceTest 以 mock 驗證 */
        User first = createUser("it-tie-a", UserStatus.ACTIVE);
        User second = createUser("it-tie-b", UserStatus.ACTIVE);
        givenPublishedCounts(Map.of(first.getUuid(), 3L, second.getUuid(), 3L));

        List<UUID> run1 = publicAuthorService.searchAuthors("it-tie").stream().map(AuthorSummaryResponse::uuid).toList();
        List<UUID> run2 = publicAuthorService.searchAuthors("it-tie").stream().map(AuthorSummaryResponse::uuid).toList();

        assertThat(run1).containsExactly(first.getUuid(), second.getUuid()).isEqualTo(run2);
    }

    @Test
    @DisplayName("authors：21 位符合者只回傳 20 位（上限），且是排序後最前面的 20 位")
    void searchAuthors_returnsAtMostTwenty() {
        Map<UUID, Long> counts = new HashMap<>();
        List<User> batch = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            batch.add(newUser(String.format("it-cap-%02d", i), UserStatus.ACTIVE));
        }
        Iterable<User> saved = userRepository.saveAll(batch);
        int i = 1;
        for (User user : saved) {
            createdUsers.add(user);
            counts.put(user.getUuid(), (long) i++);
        }
        givenPublishedCounts(counts);

        List<AuthorSummaryResponse> result = publicAuthorService.searchAuthors("it-cap");

        assertThat(result).hasSize(PublicAuthorService.MAX_AUTHORS);
        assertThat(result.get(0).publishedArticleCount()).isEqualTo(21L);
        assertThat(result.get(19).publishedArticleCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("authors：候選作者超過 BATCH_SIZE → 切批後 SQL 仍可執行，跨批結果完整")
    void searchAuthors_moreCandidatesThanBatchSize_worksAcrossBatches() {
        int total = BatchedQuery.BATCH_SIZE + 30;
        List<User> batch = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            batch.add(newUser(String.format("it-big-%04d", i), UserStatus.ACTIVE));
        }
        Map<UUID, Long> counts = new HashMap<>();
        long n = 1;
        for (User user : userRepository.saveAll(batch)) {
            createdUsers.add(user);
            counts.put(user.getUuid(), n++);
        }
        counts.put(UUID.randomUUID(), 9999L);
        givenPublishedCounts(counts);

        List<AuthorSummaryResponse> result = publicAuthorService.searchAuthors("it-big");

        assertThat(result).hasSize(20);
        assertThat(result.get(0).publishedArticleCount()).isEqualTo(total);
    }

    @Test
    @DisplayName("authors：keyword 超過 50 字 → 400 PARAM_VALID_ERROR")
    void searchAuthors_keywordLongerThanFifty_throws() {
        assertThatThrownBy(() -> publicAuthorService.searchAuthors("x".repeat(51)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.PARAM_VALID_ERROR));
    }

    @Test
    @DisplayName("authors：至少有一位作者時 keyword 含 NUL → 400 PARAM_VALID_ERROR（不得讓 PostgreSQL 拒絕 0x00 變成 500）")
    void searchAuthors_keywordContainingNul_throwsParamValidError() {
        User author = createUser("it-nul-author", UserStatus.ACTIVE);
        givenPublishedCounts(Map.of(author.getUuid(), 1L));

        assertThatThrownBy(() -> publicAuthorService.searchAuthors("a\u0000b"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.PARAM_VALID_ERROR));
    }
}
