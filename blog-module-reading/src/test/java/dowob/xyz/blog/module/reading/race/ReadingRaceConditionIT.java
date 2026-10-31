package dowob.xyz.blog.module.reading.race;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.reading.support.ReadingCleanupITSupport;
import dowob.xyz.blog.module.reading.support.SeededReadingUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 文章按讚與收藏的雙擊／併發整合測試（真實 Postgres、經 MockMvc → controller → service → 交易，不 mock 任何約束）。
 *
 * <ul>
 *   <li><b>RACE-01</b>：同一使用者對同一文章重複按讚。舊實作「先 find、再 save、catch
 *       DataIntegrityViolationException」在併發落敗時會讓交易變成 rollback-only，導致 500；
 *       新實作 {@code INSERT ... ON CONFLICT DO NOTHING}，依 affected rows 決定是否 {@code like_count + 1}。</li>
 *   <li><b>RACE-04</b>：同一使用者對同一文章重複收藏。舊實作無 DataIntegrityViolationException 處理，
 *       雙擊落敗者得到 500；新實作同樣 {@code ON CONFLICT DO NOTHING}（收藏無計數）。</li>
 *   <li>取消讚依 delete affected rows 決定是否 {@code like_count - 1}，併發雙取消不會扣成負數。</li>
 * </ul>
 *
 * <p>併發案例以 {@link CyclicBarrier} 讓兩個執行緒同時送出請求，並重複多輪。正確實作在任何交錯下
 * 結果都相同（only +1／-1、皆回 200），因此這些斷言本身不會是 flaky 的來源。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
@DisplayName("按讚與收藏併發整合測試（RACE-01 重複按讚、RACE-04 重複收藏）")
class ReadingRaceConditionIT extends ReadingCleanupITSupport {

    /** 併發案例重複的輪數 */
    private static final int ROUNDS = 25;

    @Autowired private MockMvc mockMvc;

    private RequestPostProcessor asUser() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.USER.getSpringSecurityRole()));
        Role.USER.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                new AuthUser(USER_UUID, Role.USER), null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    private int statusOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.with(asUser())).andReturn().getResponse().getStatus();
    }

    /**
     * 讓兩個執行緒同時執行同一個動作，回傳兩者的 HTTP 狀態碼（任一執行緒拋例外會讓測試失敗）。
     */
    private List<Integer> runConcurrently(Callable<Integer> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return action.call();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    // ─── RACE-01：按讚 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("RACE-01：同一使用者對同一文章連續按讚兩次 → 兩次都 200、like_count 只 +1、只 1 列")
    void likeTwiceSequentially_bothOk_countOnce_oneRow() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);

        for (int i = 0; i < 2; i++) {
            assertThat(statusOf(post("/api/v1/articles/{uuid}/like", article.getUuid())))
                    .as("第 %d 次按讚", i + 1).isEqualTo(200);
        }

        assertThat(likeCountOf(article.getUuid())).isEqualTo(1);
        assertThat(countLikes(article.getUuid())).isEqualTo(1);
    }

    @Test
    @DisplayName("RACE-01：兩條執行緒同時按讚同一文章（重複多輪）→ 全部 200、like_count 只 +1、只 1 列")
    void likeConcurrently_bothOk_countOnce_oneRow() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Article article = createArticle(ArticleStatus.PUBLISHED);

            List<Integer> statuses = runConcurrently(() ->
                    statusOf(post("/api/v1/articles/{uuid}/like", article.getUuid())));

            assertThat(statuses).as("第 %d 輪的 HTTP 狀態", round).containsExactly(200, 200);
            assertThat(likeCountOf(article.getUuid())).as("第 %d 輪的 like_count", round).isEqualTo(1);
            assertThat(countLikes(article.getUuid())).as("第 %d 輪的 user_article_likes 列數", round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("按讚後取消再按讚：like_count 與列數維持一致（ON CONFLICT 不影響正常路徑）")
    void likeUnlikeLike_staysConsistent() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);

        statusOf(post("/api/v1/articles/{uuid}/like", article.getUuid()));
        statusOf(delete("/api/v1/articles/{uuid}/like", article.getUuid()));
        assertThat(likeCountOf(article.getUuid())).isZero();
        assertThat(countLikes(article.getUuid())).isZero();

        statusOf(post("/api/v1/articles/{uuid}/like", article.getUuid()));
        assertThat(likeCountOf(article.getUuid())).isEqualTo(1);
        assertThat(countLikes(article.getUuid())).isEqualTo(1);
    }

    @Test
    @DisplayName("取消讚依 delete affected rows：連續取消兩次 → 兩次 200、like_count 只 -1（不會變負數）")
    void unlikeTwiceSequentially_countDecrementedOnce() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        statusOf(post("/api/v1/articles/{uuid}/like", article.getUuid()));

        for (int i = 0; i < 2; i++) {
            assertThat(statusOf(delete("/api/v1/articles/{uuid}/like", article.getUuid()))).isEqualTo(200);
        }

        assertThat(likeCountOf(article.getUuid())).isZero();
    }

    @Test
    @DisplayName("兩條執行緒同時取消讚（重複多輪）→ 全部 200、like_count 回到 0、不為負")
    void unlikeConcurrently_countDecrementedOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Article article = createArticle(ArticleStatus.PUBLISHED);
            statusOf(post("/api/v1/articles/{uuid}/like", article.getUuid()));

            List<Integer> statuses = runConcurrently(() ->
                    statusOf(delete("/api/v1/articles/{uuid}/like", article.getUuid())));

            assertThat(statuses).as("第 %d 輪的 HTTP 狀態", round).containsExactly(200, 200);
            assertThat(likeCountOf(article.getUuid())).as("第 %d 輪的 like_count", round).isZero();
            assertThat(countLikes(article.getUuid())).isZero();
        }
    }

    // ─── RACE-04：收藏 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("RACE-04：同一使用者對同一文章連續收藏兩次 → 兩次都 200、只 1 列")
    void bookmarkTwiceSequentially_bothOk_oneRow() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);

        for (int i = 0; i < 2; i++) {
            assertThat(statusOf(post("/api/v1/articles/{uuid}/bookmark", article.getUuid())))
                    .as("第 %d 次收藏", i + 1).isEqualTo(200);
        }

        assertThat(countBookmarks(article.getUuid())).isEqualTo(1);
    }

    @Test
    @DisplayName("RACE-04：兩條執行緒同時收藏同一文章（重複多輪）→ 全部 200、只 1 列")
    void bookmarkConcurrently_bothOk_oneRow() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Article article = createArticle(ArticleStatus.PUBLISHED);

            List<Integer> statuses = runConcurrently(() ->
                    statusOf(post("/api/v1/articles/{uuid}/bookmark", article.getUuid())));

            assertThat(statuses).as("第 %d 輪的 HTTP 狀態", round).containsExactly(200, 200);
            assertThat(countBookmarks(article.getUuid())).as("第 %d 輪的 user_bookmarks 列數", round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("收藏後取消再收藏：列數維持一致；取消兩次也都 200")
    void bookmarkUnbookmarkBookmark_staysConsistent() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);

        statusOf(post("/api/v1/articles/{uuid}/bookmark", article.getUuid()));
        assertThat(statusOf(delete("/api/v1/articles/{uuid}/bookmark", article.getUuid()))).isEqualTo(200);
        assertThat(statusOf(delete("/api/v1/articles/{uuid}/bookmark", article.getUuid()))).isEqualTo(200);
        assertThat(countBookmarks(article.getUuid())).isZero();

        statusOf(post("/api/v1/articles/{uuid}/bookmark", article.getUuid()));
        assertThat(countBookmarks(article.getUuid())).isEqualTo(1);
    }

    @Test
    @DisplayName("兩條執行緒同時取消收藏（重複多輪）→ 全部 200、列數為 0")
    void unbookmarkConcurrently_bothOk_zeroRows() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Article article = createArticle(ArticleStatus.PUBLISHED);
            insertBookmark(USER_UUID, article.getUuid());

            List<Integer> statuses = runConcurrently(() ->
                    statusOf(delete("/api/v1/articles/{uuid}/bookmark", article.getUuid())));

            assertThat(statuses).as("第 %d 輪的 HTTP 狀態", round).containsExactly(200, 200);
            assertThat(countBookmarks(article.getUuid())).isZero();
        }
    }

    @Test
    @DisplayName("不同使用者各自按讚同一文章：互不衝突，like_count 為 2")
    void differentUsersLikeSameArticle_bothCounted() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        statusOf(post("/api/v1/articles/{uuid}/like", article.getUuid()));
        UUID other = SeededReadingUsers.OTHER_USER_UUID;
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.USER.getSpringSecurityRole()));
        Role.USER.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                new AuthUser(other, Role.USER), null, authorities);

        int status = mockMvc.perform(post("/api/v1/articles/{uuid}/like", article.getUuid())
                .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
        assertThat(likeCountOf(article.getUuid())).isEqualTo(2);
        assertThat(countLikes(article.getUuid())).isEqualTo(2);
    }
}
