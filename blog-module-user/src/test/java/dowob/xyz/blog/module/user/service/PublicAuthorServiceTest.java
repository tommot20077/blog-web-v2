package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.HttpStatusBusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.AuthorArticleStats;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.user.mapper.UserMapper;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.dto.response.AuthorSummaryResponse;
import dowob.xyz.blog.module.user.model.dto.response.PublicProfileResponse;
import dowob.xyz.blog.module.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PublicAuthorService} 單元測試
 *
 * <p>SQL 行為（ACTIVE 篩選、ILIKE 跳脫）由 {@code PublicAuthorIntegrationTest} 對真實 PostgreSQL 驗證；
 * 本類以 mock 驗證：404 不可區分、欄位映射與不合格舊值回 null、keyword 規則、排序／上限／切批的接線。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
class PublicAuthorServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ArticleFacade articleFacade;

    private PublicAuthorService service;

    @BeforeEach
    void setUp() {
        service = new PublicAuthorService(userRepository, userMapper, articleFacade);
    }

    private User user(UUID uuid, UserStatus status) {
        User user = new User();
        user.setId(987654L);
        user.setUuid(uuid);
        user.setEmail("secret@example.com");
        user.setUsername("login-name-sentinel");
        user.setNickname("Alice");
        user.setAvatarUrl("https://img.example.com/a.png");
        user.setBio("bio");
        user.setLocation("Taipei");
        user.setWebsite("https://alice.example.com");
        user.setSocialLinks("{\"github\":\"alice\"}");
        user.setRole(Role.AUTHOR);
        user.setStatus(status);
        user.setCreatedAt(LocalDateTime.of(2025, 3, 4, 5, 6));
        return user;
    }

    private User projection(UUID uuid, String nickname) {
        User user = new User();
        user.setUuid(uuid);
        user.setNickname(nickname);
        user.setAvatarUrl(null);
        return user;
    }

    @Nested
    @DisplayName("getPublicProfile")
    class GetPublicProfile {

        @Test
        @DisplayName("ACTIVE 使用者 → 回傳公開欄位與統計，社群連結為 handle map")
        void activeUser_returnsPublicFieldsAndStats() {
            UUID uuid = UUID.randomUUID();
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(user(uuid, UserStatus.ACTIVE)));
            when(articleFacade.getPublishedStatsByAuthor(uuid)).thenReturn(new AuthorArticleStats(3L, 1500L, 42L));

            PublicProfileResponse response = service.getPublicProfile(uuid);

            assertThat(response.uuid()).isEqualTo(uuid);
            assertThat(response.nickname()).isEqualTo("Alice");
            assertThat(response.avatarUrl()).isEqualTo("https://img.example.com/a.png");
            assertThat(response.bio()).isEqualTo("bio");
            assertThat(response.location()).isEqualTo("Taipei");
            assertThat(response.website()).isEqualTo("https://alice.example.com");
            assertThat(response.socialLinks()).containsEntry("github", "alice").containsKeys("twitter", "linkedin");
            assertThat(response.joinedAt()).isEqualTo(LocalDateTime.of(2025, 3, 4, 5, 6));
            assertThat(response.stats()).isEqualTo(new PublicProfileResponse.Stats(3L, 1500L, 42L));
        }

        @Test
        @DisplayName("沒有已發布文章的 ACTIVE 使用者 → 照常回傳，統計為 0")
        void activeUserWithoutArticles_returnsZeroStats() {
            UUID uuid = UUID.randomUUID();
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(user(uuid, UserStatus.ACTIVE)));
            when(articleFacade.getPublishedStatsByAuthor(uuid)).thenReturn(AuthorArticleStats.EMPTY);

            PublicProfileResponse response = service.getPublicProfile(uuid);

            assertThat(response.stats()).isEqualTo(new PublicProfileResponse.Stats(0L, 0L, 0L));
        }

        @Test
        @DisplayName("不存在、DELETED、PENDING_VERIFICATION、BANNED → 同一個 404 A0101，錯誤碼與訊息完全相同，且不查統計")
        void nonActiveOrMissing_allThrowIdenticalNotFound() {
            UUID missing = UUID.randomUUID();
            UUID deleted = UUID.randomUUID();
            UUID pending = UUID.randomUUID();
            UUID banned = UUID.randomUUID();
            when(userRepository.findByUuid(missing)).thenReturn(Optional.empty());
            when(userRepository.findByUuid(deleted)).thenReturn(Optional.of(user(deleted, UserStatus.DELETED)));
            when(userRepository.findByUuid(pending)).thenReturn(Optional.of(user(pending, UserStatus.PENDING_VERIFICATION)));
            when(userRepository.findByUuid(banned)).thenReturn(Optional.of(user(banned, UserStatus.BANNED)));

            List<HttpStatusBusinessException> thrown = new ArrayList<>();
            for (UUID uuid : List.of(missing, deleted, pending, banned)) {
                assertThatThrownBy(() -> service.getPublicProfile(uuid))
                        .isInstanceOf(HttpStatusBusinessException.class)
                        .satisfies(e -> thrown.add((HttpStatusBusinessException) e));
            }

            assertThat(thrown).hasSize(4).allSatisfy(e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.USER_NOT_FOUND);
                assertThat(e.getMessage()).isEqualTo(thrown.get(0).getMessage());
            });
            verifyNoInteractions(articleFacade);
        }

        @Test
        @DisplayName("website 不合格的舊資料（javascript:、ftp:）→ 回 null；avatarUrl 不合格 → 回 null")
        void unsafeLegacyUrls_areNull() {
            UUID uuid = UUID.randomUUID();
            User legacy = user(uuid, UserStatus.ACTIVE);
            legacy.setWebsite("javascript:alert(1)");
            legacy.setAvatarUrl("ftp://evil.example.com/a.png");
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(legacy));
            when(articleFacade.getPublishedStatsByAuthor(uuid)).thenReturn(AuthorArticleStats.EMPTY);

            PublicProfileResponse response = service.getPublicProfile(uuid);

            assertThat(response.website()).isNull();
            assertThat(response.avatarUrl()).isNull();
        }

        @Test
        @DisplayName("socialLinks 為 null 或亂碼 → 不拋例外，三個已知 key 為 null")
        void garbageSocialLinks_neverThrows() {
            UUID uuid = UUID.randomUUID();
            User legacy = user(uuid, UserStatus.ACTIVE);
            legacy.setSocialLinks("{{{ not json");
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(legacy));
            when(articleFacade.getPublishedStatsByAuthor(uuid)).thenReturn(AuthorArticleStats.EMPTY);

            PublicProfileResponse response = service.getPublicProfile(uuid);

            assertThat(response.socialLinks()).containsKeys("github", "twitter", "linkedin");
            assertThat(response.socialLinks().values()).containsOnlyNulls();
        }

        @Test
        @DisplayName("回應型別的欄位集合固定，不含 email／username／role／status／通知偏好／id")
        void responseComponents_areWhitelisted() {
            List<String> names = java.util.Arrays.stream(PublicProfileResponse.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).toList();

            assertThat(names).containsExactly("uuid", "nickname", "avatarUrl", "bio", "location",
                    "website", "socialLinks", "joinedAt", "stats");
        }
    }

    @Nested
    @DisplayName("searchAuthors")
    class SearchAuthors {

        @Test
        @DisplayName("keyword trim 後超過 50 字 → 400 PARAM_VALID_ERROR，且不查 facade 與 DB")
        void keywordLongerThanFifty_throwsParamValidError() {
            assertThatThrownBy(() -> service.searchAuthors("a".repeat(51)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(CommonErrorCode.PARAM_VALID_ERROR));

            verifyNoInteractions(articleFacade, userMapper);
        }

        @Test
        @DisplayName("keyword 恰 50 字（含前後空白不計）→ 通過")
        void keywordExactlyFifty_afterTrim_isAccepted() {
            when(articleFacade.countPublishedByAuthor()).thenReturn(Map.of());

            assertThat(service.searchAuthors("  " + "a".repeat(50) + "  ")).isEmpty();
        }

        @Test
        @DisplayName("沒有任何作者有已發布文章 → 回空清單，不查 users")
        void noPublishedAuthors_returnsEmptyWithoutQueryingUsers() {
            when(articleFacade.countPublishedByAuthor()).thenReturn(Map.of());

            assertThat(service.searchAuthors(null)).isEmpty();

            verify(userMapper, never()).findActiveAuthorsByUuids(anyList(), any());
        }

        @Test
        @DisplayName("keyword 為 null 或空白 → 不加暱稱條件（pattern 傳 null）")
        void blankKeyword_passesNullPattern() {
            UUID a = UUID.randomUUID();
            when(articleFacade.countPublishedByAuthor()).thenReturn(Map.of(a, 1L));
            when(userMapper.findActiveAuthorsByUuids(anyList(), isNull())).thenReturn(List.of(projection(a, "A")));

            assertThat(service.searchAuthors("   ")).hasSize(1);
            assertThat(service.searchAuthors(null)).hasSize(1);

            verify(userMapper, times(2)).findActiveAuthorsByUuids(anyList(), isNull());
        }

        @Test
        @DisplayName("keyword 前後空白去除，% _ \\ 跳脫為字面，前後加 %")
        void keyword_isTrimmedAndEscaped() {
            UUID a = UUID.randomUUID();
            when(articleFacade.countPublishedByAuthor()).thenReturn(Map.of(a, 1L));
            when(userMapper.findActiveAuthorsByUuids(anyList(), any())).thenReturn(List.of());

            service.searchAuthors("  100%_a\\b  ");

            ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
            verify(userMapper).findActiveAuthorsByUuids(anyList(), pattern.capture());
            assertThat(pattern.getValue()).isEqualTo("%100\\%\\_a\\\\b%");
        }

        @Test
        @DisplayName("排序：篇數 DESC → 暱稱 ASC → uuid ASC；映射 uuid／nickname／avatarUrl／篇數")
        void results_areSortedByCountThenNicknameThenUuid() {
            UUID mostPosts = new UUID(0L, 9L);
            UUID tieB1 = new UUID(0L, 2L);
            UUID tieB2 = new UUID(0L, 1L);
            UUID tieA = new UUID(0L, 3L);
            UUID fewest = new UUID(0L, 4L);
            Map<UUID, Long> counts = new HashMap<>();
            counts.put(mostPosts, 10L);
            counts.put(tieB1, 5L);
            counts.put(tieB2, 5L);
            counts.put(tieA, 5L);
            counts.put(fewest, 1L);
            when(articleFacade.countPublishedByAuthor()).thenReturn(counts);
            User withAvatar = projection(mostPosts, "Zed");
            withAvatar.setAvatarUrl("https://img.example.com/z.png");
            when(userMapper.findActiveAuthorsByUuids(anyList(), isNull())).thenReturn(List.of(
                    projection(fewest, "Amy"), projection(tieB1, "Bob"), projection(tieB2, "Bob"),
                    projection(tieA, "Ann"), withAvatar));

            List<AuthorSummaryResponse> result = service.searchAuthors(null);

            assertThat(result).extracting(AuthorSummaryResponse::uuid)
                    .containsExactly(mostPosts, tieA, tieB2, tieB1, fewest);
            assertThat(result.get(0)).isEqualTo(
                    new AuthorSummaryResponse(mostPosts, "Zed", "https://img.example.com/z.png", 10L));
        }

        @Test
        @DisplayName("上限：超過 20 位只回傳排序後的前 20 位")
        void results_areCappedAtTwenty() {
            Map<UUID, Long> counts = new HashMap<>();
            List<User> users = new ArrayList<>();
            for (int i = 1; i <= 25; i++) {
                UUID uuid = new UUID(1L, i);
                counts.put(uuid, (long) i);
                users.add(projection(uuid, "Author" + i));
            }
            when(articleFacade.countPublishedByAuthor()).thenReturn(counts);
            when(userMapper.findActiveAuthorsByUuids(anyList(), isNull())).thenReturn(users);

            List<AuthorSummaryResponse> result = service.searchAuthors(null);

            assertThat(result).hasSize(PublicAuthorService.MAX_AUTHORS);
            assertThat(result.get(0).publishedArticleCount()).isEqualTo(25L);
            assertThat(result.get(19).publishedArticleCount()).isEqualTo(6L);
        }

        @Test
        @DisplayName("非 ACTIVE（mapper 未回傳）的作者不出現，即使 facade 有計數")
        void authorsAbsentFromMapper_areDropped() {
            UUID active = UUID.randomUUID();
            UUID inactive = UUID.randomUUID();
            when(articleFacade.countPublishedByAuthor()).thenReturn(Map.of(active, 2L, inactive, 9L));
            when(userMapper.findActiveAuthorsByUuids(anyList(), isNull())).thenReturn(List.of(projection(active, "A")));

            assertThat(service.searchAuthors(null)).extracting(AuthorSummaryResponse::uuid).containsExactly(active);
        }

        @Test
        @DisplayName("作者數超過 BatchedQuery.BATCH_SIZE → 切批查 users，每批不超過上限，結果合併後再排序取前 20")
        void moreAuthorsThanBatchSize_areQueriedInBatches() {
            int total = BatchedQuery.BATCH_SIZE + 100;
            Map<UUID, Long> counts = new HashMap<>();
            IntStream.range(0, total).forEach(i -> counts.put(new UUID(2L, i), (long) (i + 1)));
            when(articleFacade.countPublishedByAuthor()).thenReturn(counts);
            List<Integer> batchSizes = new ArrayList<>();
            when(userMapper.findActiveAuthorsByUuids(anyList(), isNull())).thenAnswer(invocation -> {
                List<UUID> batch = invocation.getArgument(0);
                batchSizes.add(batch.size());
                return batch.stream().map(uuid -> projection(uuid, "N" + uuid.getLeastSignificantBits())).toList();
            });

            List<AuthorSummaryResponse> result = service.searchAuthors(null);

            assertThat(batchSizes).containsExactlyInAnyOrder(BatchedQuery.BATCH_SIZE, 100);
            assertThat(result).hasSize(20);
            assertThat(result.get(0).publishedArticleCount()).isEqualTo(total);
        }
    }
}
