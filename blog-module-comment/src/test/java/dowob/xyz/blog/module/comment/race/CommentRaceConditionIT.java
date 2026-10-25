package dowob.xyz.blog.module.comment.race;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.support.CommentCleanupITSupport;
import dowob.xyz.blog.module.comment.support.SeededUsers;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 留言模組兩個併發問題的整合測試（真實 Postgres、經 MockMvc → controller → service → 交易，不 mock 任何約束）。
 *
 * <ul>
 *   <li><b>RACE-01</b>：同一使用者對同一留言重複按讚。舊實作「先 find、再 save、catch
 *       DataIntegrityViolationException」在併發落敗時會讓交易變成 rollback-only，導致 500；
 *       新實作 {@code INSERT ... ON CONFLICT DO NOTHING}，依 affected rows 決定是否 +1。</li>
 *   <li><b>RACE-09</b>：同一則留言被併發刪除兩次。舊的 {@code softDelete} 缺
 *       {@code AND deleted_at IS NULL}，兩個交易都讀到「尚未刪除」就都會扣 {@code articles.comment_count}；
 *       新實作依 affected rows 決定是否 -1。</li>
 * </ul>
 *
 * <p>併發案例以 {@link CyclicBarrier} 讓兩個執行緒同時送出請求，並重複多輪。正確實作在任何交錯下
 * 結果都相同（like_count 只 +1、comment_count 只 -1、皆回 200），因此這些斷言本身不會是 flaky 的來源。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
@DisplayName("留言併發問題整合測試（RACE-01 重複按讚、RACE-09 雙刪雙扣）")
class CommentRaceConditionIT extends CommentCleanupITSupport {

    /** 併發案例重複的輪數 */
    private static final int ROUNDS = 25;

    @Autowired private MockMvc mockMvc;
    @Autowired private CommentMapper commentMapper;

    private RequestPostProcessor asUser(UUID userUuid, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                new AuthUser(userUuid.getLeastSignificantBits(), userUuid, role), null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    private int likeCountOf(UUID commentUuid) {
        return jdbc.queryForObject("SELECT like_count FROM comments WHERE uuid = ?", Integer.class, commentUuid);
    }

    private int likeRowsOf(Long commentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id = ?", Integer.class, commentId);
    }

    private int articleCommentCount(Article article) {
        return jdbc.queryForObject("SELECT comment_count FROM articles WHERE id = ?", Integer.class, article.getId());
    }

    private void setArticleCommentCount(Article article, int count) {
        jdbc.update("UPDATE articles SET comment_count = ? WHERE id = ?", count, article.getId());
    }

    /**
     * 讓兩個執行緒同時執行同一個動作，回傳兩者的 HTTP 狀態碼（任一執行緒拋例外會讓測試失敗）。
     */
    private List<Integer> runConcurrently(java.util.concurrent.Callable<Integer> action) throws Exception {
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

    // ─── RACE-01 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("RACE-01：同一使用者對同一留言連續按讚兩次 → 兩次都 200、like_count 只 +1、comment_likes 只 1 列")
    void likeTwiceSequentially_bothOk_countOnce_oneRow() throws Exception {
        Comment comment = createComment(createArticle(ArticleStatus.PUBLISHED), null);

        for (int i = 0; i < 2; i++) {
            int status = mockMvc.perform(post("/api/v1/comments/{uuid}/like", comment.getUuid())
                    .with(asUser(SeededUsers.USER_UUID, Role.USER))).andReturn().getResponse().getStatus();
            assertThat(status).as("第 %d 次按讚", i + 1).isEqualTo(200);
        }

        assertThat(likeCountOf(comment.getUuid())).isEqualTo(1);
        assertThat(likeRowsOf(comment.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("RACE-01：兩條執行緒同時按讚同一留言（重複多輪）→ 全部 200、like_count 只 +1、comment_likes 只 1 列")
    void likeConcurrently_bothOk_countOnce_oneRow() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);

        for (int round = 0; round < ROUNDS; round++) {
            Comment comment = createComment(article, null);

            List<Integer> statuses = runConcurrently(() ->
                    mockMvc.perform(post("/api/v1/comments/{uuid}/like", comment.getUuid())
                            .with(asUser(SeededUsers.USER_UUID, Role.USER)))
                            .andReturn().getResponse().getStatus());

            assertThat(statuses).as("第 %d 輪的 HTTP 狀態", round).containsExactly(200, 200);
            assertThat(likeCountOf(comment.getUuid())).as("第 %d 輪的 like_count", round).isEqualTo(1);
            assertThat(likeRowsOf(comment.getId())).as("第 %d 輪的 comment_likes 列數", round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("按讚後取消再按讚：like_count 與 comment_likes 維持一致（ON CONFLICT 不影響正常路徑）")
    void likeUnlikeLike_staysConsistent() throws Exception {
        Comment comment = createComment(createArticle(ArticleStatus.PUBLISHED), null);
        RequestPostProcessor user = asUser(SeededUsers.USER_UUID, Role.USER);

        mockMvc.perform(post("/api/v1/comments/{uuid}/like", comment.getUuid()).with(user));
        mockMvc.perform(delete("/api/v1/comments/{uuid}/like", comment.getUuid()).with(user));
        assertThat(likeCountOf(comment.getUuid())).isZero();
        assertThat(likeRowsOf(comment.getId())).isZero();

        mockMvc.perform(post("/api/v1/comments/{uuid}/like", comment.getUuid()).with(user));
        assertThat(likeCountOf(comment.getUuid())).isEqualTo(1);
        assertThat(likeRowsOf(comment.getId())).isEqualTo(1);
    }

    // ─── RACE-09 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("RACE-09：同一則留言依序刪除兩次（第二次走 idempotent 路徑）→ articles.comment_count 只 -1")
    void deleteTwiceSequentially_articleCountDecrementedOnce() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        setArticleCommentCount(article, 2);
        Comment comment = createComment(article, null);

        for (int i = 0; i < 2; i++) {
            int status = mockMvc.perform(delete("/api/v1/comments/{uuid}", comment.getUuid())
                    .with(asUser(SeededUsers.USER_UUID, Role.USER))).andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(200);
        }

        assertThat(articleCommentCount(article)).isEqualTo(1);
    }

    @Test
    @DisplayName("RACE-09：softDelete 只對尚未刪除的留言生效——第一次回 1、第二次回 0（模擬兩個交易都讀到未刪除）")
    void softDelete_secondCallAffectsNoRows() {
        Comment comment = createComment(createArticle(ArticleStatus.PUBLISHED), null);

        int first = commentMapper.softDelete(comment.getId(), "AUTHOR");
        int second = commentMapper.softDelete(comment.getId(), "ADMIN");

        assertThat(first).isEqualTo(1);
        assertThat(second).as("第二個交易的 UPDATE 不得再命中已刪除的列").isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_by_role FROM comments WHERE id = ?", String.class, comment.getId()))
                .as("先刪除者的 deleted_by_role 不得被覆寫").isEqualTo("AUTHOR");
    }

    @Test
    @DisplayName("RACE-09：兩條執行緒同時刪除同一則留言（重複多輪）→ 全部 200、articles.comment_count 只 -1")
    void deleteConcurrently_articleCountDecrementedOnce() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);

        for (int round = 0; round < ROUNDS; round++) {
            setArticleCommentCount(article, 5);
            Comment comment = createComment(article, null);

            List<Integer> statuses = runConcurrently(() ->
                    mockMvc.perform(delete("/api/v1/comments/{uuid}", comment.getUuid())
                            .with(asUser(SeededUsers.USER_UUID, Role.USER)))
                            .andReturn().getResponse().getStatus());

            assertThat(statuses).as("第 %d 輪的 HTTP 狀態", round).containsExactly(200, 200);
            assertThat(articleCommentCount(article)).as("第 %d 輪的 articles.comment_count", round).isEqualTo(4);
        }
    }
}
