package dowob.xyz.blog.module.comment.support;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.comment.config.CommentTestApplication;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.CommentLike;
import dowob.xyz.blog.module.comment.repository.CommentLikeRepository;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文章刪除連動（consumer 與孤兒校正）整合測試的共用基底。
 *
 * <p>以<b>單例</b> Postgres／Redis 容器（static 區塊啟動、不隨測試類別停止，由 Testcontainers 的 Ryuk 收尾）：
 * 多個 IT 類別共用同一個 Spring context 快取時，若容器逐類別重啟，快取中的 context 會指向已關閉的容器。</p>
 *
 * <p>提供建立文章、留言、留言讚與製造孤兒留言的輔助方法；每個測試結束後清空 comments、comment_likes 與 articles。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = CommentTestApplication.class)
@ActiveProfiles("test")
public abstract class CommentCleanupITSupport {

    /** 單例 Postgres 容器 */
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("blog_test")
            .withUsername("test")
            .withPassword("test");

    /** 單例 Redis 容器 */
    protected static final com.redis.testcontainers.RedisContainer REDIS =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * 註冊容器連線屬性。
     *
     * @param registry 動態屬性註冊表
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url", REDIS::getRedisURI);
    }

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    /** article 模組的 ArticleFileBinder 依賴 FileFacade，其實作在未被本測試 scan 的 blog-module-file */
    @MockitoBean private FileFacade fileFacade;

    @Autowired protected ArticleRepository articleRepo;
    @Autowired protected CommentRepository commentRepo;
    @Autowired protected CommentLikeRepository commentLikeRepo;
    @Autowired protected JdbcTemplate jdbc;

    /** seed 資料中的使用者公開 UUID（固定值，見 {@link SeededUsers}） */
    protected static final UUID USER_UUID = SeededUsers.USER_UUID;

    /** 清空本測試建立的資料（comments 先於 articles）。 */
    @AfterEach
    void cleanUpCommentCleanupData() {
        jdbc.update("DELETE FROM comment_likes");
        jdbc.update("DELETE FROM comments");
        articleRepo.deleteAll();
    }

    /**
     * 建立一篇文章。
     *
     * @param status 文章狀態
     * @return 已儲存的文章
     */
    protected Article createArticle(ArticleStatus status) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorUuid(USER_UUID);
        article.setTitle("cleanup-it");
        article.setSlug("cleanup-it-" + UUID.randomUUID());
        article.setContent("test");
        article.setContentHtml("<p>test</p>");
        article.setStatus(status);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article);
    }

    /**
     * 為指定文章建立一則留言。
     *
     * @param article  所屬文章
     * @param parentId 父留言主鍵；null 表示頂層
     * @return 已儲存的留言
     */
    protected Comment createComment(Article article, Long parentId) {
        Comment comment = new Comment();
        comment.setUuid(UUID.randomUUID());
        comment.setArticleUuid(article.getUuid());
        comment.setParentId(parentId);
        comment.setUserUuid(USER_UUID);
        comment.setContent("c");
        comment.setContentHtml("<p>c</p>");
        comment.setLikeCount(0);
        return commentRepo.save(comment);
    }

    /**
     * 為指定留言建立一筆留言讚。
     *
     * @param commentId 留言主鍵
     * @param userUuid  按讚者公開 UUID
     */
    protected void createLike(Long commentId, UUID userUuid) {
        CommentLike like = new CommentLike();
        like.setUserUuid(userUuid);
        like.setCommentId(commentId);
        like.setCreatedAt(LocalDateTime.now());
        commentLikeRepo.save(like);
    }

    /**
     * 直接以 SQL 製造「article_uuid 指向不存在文章」的孤兒留言。
     *
     * <p>V25 起 {@code comments} 已沒有任何指向 {@code articles} 的 FK，孤兒留言可以直接寫入，
     * 不需要（也不再）停用觸發器。</p>
     *
     * @param orphanArticleUuid 不存在於 articles 的文章 UUID
     * @param parentId          父留言主鍵；null 表示頂層
     * @return 新留言主鍵
     */
    protected Long insertOrphanComment(UUID orphanArticleUuid, Long parentId) {
        return jdbc.queryForObject(
                "INSERT INTO comments (uuid, article_uuid, parent_id, user_uuid, content, content_html) "
                        + "VALUES (?, ?, ?, ?, 'orphan', '<p>orphan</p>') RETURNING id",
                Long.class, UUID.randomUUID(), orphanArticleUuid, parentId, USER_UUID);
    }

    /**
     * 計算某文章 UUID 底下的留言數。
     *
     * @param articleUuid 文章 UUID
     * @return 留言數
     */
    protected int countComments(UUID articleUuid) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE article_uuid = ?", Integer.class, articleUuid);
        return n == null ? 0 : n;
    }

    /**
     * 計算 comment_likes 總數。
     *
     * @return 留言讚總數
     */
    protected int countAllLikes() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes", Integer.class);
        return n == null ? 0 : n;
    }
}
