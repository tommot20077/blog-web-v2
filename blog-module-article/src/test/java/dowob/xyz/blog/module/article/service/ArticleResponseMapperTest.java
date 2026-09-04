package dowob.xyz.blog.module.article.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.CategoryWithArticleId;
import dowob.xyz.blog.module.article.model.TagWithArticleUuid;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.CategoryResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.TagSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.TocEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleResponseMapperTest {

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private UserFacade userFacade;

    @Mock
    private ViewCountService viewCountService;

    private final ArticleTocCodec articleTocCodec = new ArticleTocCodec(new ObjectMapper());

    private ArticleResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ArticleResponseMapper(articleMapper, categoryMapper, userFacade, viewCountService, articleTocCodec);
    }

    @Nested
    @DisplayName("toResponse")
    class ToResponse {

        @Test
        @DisplayName("maps full article response and reads live view count")
        void toResponse_completesAllFields() {
            UUID uuid = UUID.randomUUID();
            UUID authorUuid = UUID.randomUUID();
            UUID tagId = UUID.randomUUID();
            UUID categoryUuid = UUID.randomUUID();
            SeriesNavigation seriesNav = new SeriesNavigation();
            Article article = article(uuid, 5L);
            article.setContent("# md");
            article.setContentHtml("<h1>md</h1>");

            when(userFacade.getUserUuidById(5L)).thenReturn(Optional.of(authorUuid));
            when(userFacade.getUserNicknameById(5L)).thenReturn(Optional.of("Yuan"));
            when(viewCountService.getViewCount(uuid)).thenReturn(1234L);

            ArticleResponse resp = mapper.toResponse(
                    article,
                    List.of(TagSummaryResponse.builder().id(tagId).name("Java").slug("java").build()),
                    List.of(CategoryResponse.builder().uuid(categoryUuid).name("Backend").slug("backend").build()),
                    true,
                    false,
                    BigDecimal.valueOf(42),
                    seriesNav);

            assertThat(resp.getUuid()).isEqualTo(uuid);
            assertThat(resp.getTitle()).isEqualTo("Title");
            assertThat(resp.getContent()).isEqualTo("# md");
            assertThat(resp.getContentHtml()).isEqualTo("<h1>md</h1>");
            assertThat(resp.getSummary()).isEqualTo("summary");
            assertThat(resp.getAuthorUuid()).isEqualTo(authorUuid);
            assertThat(resp.getAuthorNickname()).isEqualTo("Yuan");
            assertThat(resp.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            assertThat(resp.getViewCount()).isEqualTo(1234L);
            assertThat(resp.getSlug()).isEqualTo("slug");
            assertThat(resp.getLikeCount()).isEqualTo(10L);
            assertThat(resp.getCommentCount()).isEqualTo(5);
            assertThat(resp.getTags()).extracting(TagSummaryResponse::getId).containsExactly(tagId);
            assertThat(resp.getCategories()).extracting(CategoryResponse::getUuid).containsExactly(categoryUuid);
            assertThat(resp.getLiked()).isTrue();
            assertThat(resp.getBookmarked()).isFalse();
            assertThat(resp.getLastReadProgress()).isEqualByComparingTo(BigDecimal.valueOf(42));
            assertThat(resp.getSeriesNav()).isSameAs(seriesNav);
        }

        @Test
        @DisplayName("toc正常反序列化：合法 JSON 陣列字串轉為對應 List<TocEntry>")
        void toResponse_deserializesTocJson() {
            Article article = article(UUID.randomUUID(), 5L);
            article.setToc("[{\"id\":\"heading-a\",\"text\":\"A\",\"level\":2},"
                    + "{\"id\":\"heading-b\",\"text\":\"B\",\"level\":3}]");
            when(viewCountService.getViewCount(article.getUuid())).thenReturn(0L);

            ArticleResponse resp = mapper.toResponse(article);

            assertThat(resp.getToc()).containsExactly(
                    new TocEntry("heading-a", "A", 2),
                    new TocEntry("heading-b", "B", 3));
        }

        @Test
        @DisplayName("toc為null回空陣列")
        void toResponse_nullTocReturnsEmptyList() {
            Article article = article(UUID.randomUUID(), 5L);
            article.setToc(null);
            when(viewCountService.getViewCount(article.getUuid())).thenReturn(0L);

            ArticleResponse resp = mapper.toResponse(article);

            assertThat(resp.getToc()).isEmpty();
        }

        @Test
        @DisplayName("toc為空字串回空陣列")
        void toResponse_blankTocReturnsEmptyList() {
            Article article = article(UUID.randomUUID(), 5L);
            article.setToc("");
            when(viewCountService.getViewCount(article.getUuid())).thenReturn(0L);

            ArticleResponse resp = mapper.toResponse(article);

            assertThat(resp.getToc()).isEmpty();
        }

        @Test
        @DisplayName("toc解析失敗回空陣列（不拋例外）")
        void toResponse_malformedTocReturnsEmptyListWithoutThrowing() {
            Article article = article(UUID.randomUUID(), 5L);
            article.setToc("{not-valid-json");
            when(viewCountService.getViewCount(article.getUuid())).thenReturn(0L);

            ArticleResponse resp = assertDoesNotThrow(() -> mapper.toResponse(article));

            assertThat(resp.getToc()).isEmpty();
        }
    }

    @Nested
    @DisplayName("toEditorResponse")
    class ToEditorResponse {

        @Test
        @DisplayName("maps raw editor fields with provided tags and categories")
        void toEditorResponse_includesRawFields() {
            Article article = article(UUID.randomUUID(), 5L);
            article.setTitle("Edit");
            article.setContent("raw md");
            article.setStatus(ArticleStatus.DRAFT);

            EditorArticleResponse resp = mapper.toEditorResponse(
                    article,
                    List.of(TagSummaryResponse.builder().name("Java").slug("java").build()),
                    List.of(CategoryResponse.builder().name("Backend").slug("backend").build()));

            assertThat(resp.getTitle()).isEqualTo("Edit");
            assertThat(resp.getContent()).isEqualTo("raw md");
            assertThat(resp.getStatus()).isEqualTo(ArticleStatus.DRAFT);
            assertThat(resp.getTags()).extracting(TagSummaryResponse::getName).containsExactly("Java");
            assertThat(resp.getCategories()).extracting(CategoryResponse::getName).containsExactly("Backend");
        }

        @Test
        @DisplayName("toc正常反序列化：合法 JSON 陣列字串轉為對應 List<TocEntry>")
        void toEditorResponse_deserializesTocJson() {
            Article article = article(UUID.randomUUID(), 5L);
            article.setToc("[{\"id\":\"heading-a\",\"text\":\"A\",\"level\":2}]");

            EditorArticleResponse resp = mapper.toEditorResponse(article);

            assertThat(resp.getToc()).containsExactly(new TocEntry("heading-a", "A", 2));
        }

        @Test
        @DisplayName("toc為null回空陣列")
        void toEditorResponse_nullTocReturnsEmptyList() {
            Article article = article(UUID.randomUUID(), 5L);
            article.setToc(null);

            EditorArticleResponse resp = mapper.toEditorResponse(article);

            assertThat(resp.getToc()).isEmpty();
        }

        @Test
        @DisplayName("toc解析失敗回空陣列（不拋例外）")
        void toEditorResponse_malformedTocReturnsEmptyListWithoutThrowing() {
            Article article = article(UUID.randomUUID(), 5L);
            article.setToc("not-json-at-all");

            EditorArticleResponse resp = assertDoesNotThrow(() -> mapper.toEditorResponse(article));

            assertThat(resp.getToc()).isEmpty();
        }
    }

    @Nested
    @DisplayName("toSummaryResponse")
    class ToSummaryResponse {

        @Test
        @DisplayName("uses article viewCount for list behavior")
        void toSummaryResponse_usesArticleViewCount() {
            UUID uuid = UUID.randomUUID();
            UUID authorUuid = UUID.randomUUID();
            Article article = article(uuid, 3L);
            article.setViewCount(50L);
            when(userFacade.getUserUuidById(3L)).thenReturn(Optional.of(authorUuid));
            when(userFacade.getUserNicknameById(3L)).thenReturn(Optional.of("Author"));

            ArticleSummaryResponse resp = mapper.toSummaryResponse(
                    article,
                    Map.of(uuid, List.of(TagSummaryResponse.builder().name("Java").build())));

            assertThat(resp.getTitle()).isEqualTo("Title");
            assertThat(resp.getSummary()).isEqualTo("summary");
            assertThat(resp.getViewCount()).isEqualTo(50L);
            assertThat(resp.getAuthorUuid()).isEqualTo(authorUuid);
            assertThat(resp.getAuthorNickname()).isEqualTo("Author");
            assertThat(resp.getTags()).extracting(TagSummaryResponse::getName).containsExactly("Java");
            verify(viewCountService, never()).getViewCount(uuid);
        }

        @Test
        @DisplayName("summary不含toc：ArticleSummaryResponse 不應暴露 toc 欄位（列表頁不需要，避免無謂 payload）")
        void toSummaryResponse_hasNoTocField() {
            UUID uuid = UUID.randomUUID();
            Article article = article(uuid, 3L);
            article.setToc("[{\"id\":\"heading-a\",\"text\":\"A\",\"level\":2}]");

            assertThat(ArticleSummaryResponse.class.getDeclaredFields())
                    .extracting(java.lang.reflect.Field::getName)
                    .doesNotContain("toc");

            // 即便來源 article 帶有合法 toc JSON，toSummaryResponse 仍不應嘗試處理它、也不應丟出例外
            assertThat(assertDoesNotThrow(() -> mapper.toSummaryResponse(article, List.of()))).isNotNull();
        }
    }

    @Nested
    @DisplayName("child mappers")
    class ChildMappers {

        @Test
        @DisplayName("maps TagInfo records to TagSummaryResponse.id")
        void toTagSummaryResponses_mapsTagInfoId() {
            UUID tagId = UUID.randomUUID();

            List<TagSummaryResponse> result =
                    mapper.toTagSummaryResponses(List.of(new TagInfo(tagId, "Java", "java")));

            assertThat(result).extracting(TagSummaryResponse::getId).containsExactly(tagId);
        }

        @Test
        @DisplayName("batch maps tags by article uuid")
        void batchToTagResponsesMap_groupsByArticleUuid() {
            UUID articleUuid = UUID.randomUUID();
            UUID tagId = UUID.randomUUID();
            TagWithArticleUuid row = new TagWithArticleUuid();
            row.setArticleUuid(articleUuid);
            row.setId(tagId);
            row.setName("Java");
            row.setSlug("java");
            when(articleMapper.findTagsByArticleUuids(List.of(articleUuid))).thenReturn(List.of(row));

            Map<UUID, List<TagSummaryResponse>> result =
                    mapper.batchToTagResponsesMap(List.of(articleUuid));

            assertThat(result.get(articleUuid)).extracting(TagSummaryResponse::getId).containsExactly(tagId);
        }

        @Test
        @DisplayName("maps categories by article id")
        void toCategoryResponses_mapsCategories() {
            CategoryWithArticleId row = new CategoryWithArticleId();
            row.setArticleId(1L);
            row.setUuid(UUID.randomUUID());
            row.setName("Backend");
            row.setSlug("backend");
            row.setDescription("desc");
            row.setSortOrder(10);
            when(categoryMapper.findCategoriesByArticleIds(List.of(1L))).thenReturn(List.of(row));

            List<CategoryResponse> result = mapper.toCategoryResponses(1L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUuid()).isEqualTo(row.getUuid());
            assertThat(result.get(0).getName()).isEqualTo("Backend");
            assertThat(result.get(0).getSortOrder()).isEqualTo(10);
        }

        @Test
        @DisplayName("null inputs return empty collections")
        void nullInputs_returnEmptyCollections() {
            assertThat(mapper.toTagSummaryResponses((List<TagInfo>) null)).isEmpty();
            assertThat(mapper.toCategoryResponses(null)).isEmpty();
            assertThat(mapper.batchToTagResponsesMap(null)).isEmpty();
            assertThat(mapper.batchToCategoryResponsesMap(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("author resolver")
    class AuthorResolver {

        @Test
        @DisplayName("returns null when user facade returns empty")
        void resolveAuthor_returnsNullWhenMissing() {
            when(userFacade.getUserUuidById(7L)).thenReturn(Optional.empty());
            when(userFacade.getUserNicknameById(7L)).thenReturn(Optional.empty());

            assertThat(mapper.resolveAuthorUuid(7L)).isNull();
            assertThat(mapper.resolveAuthorNickname(7L)).isNull();
        }
    }

    /**
     * rejectReason 為 admin 撰寫的「內部審核評語」，只能對作者本人與 ADMIN 揭露。
     *
     * <p>三個 DTO（ArticleResponse / ArticleSummaryResponse / EditorArticleResponse）
     * 一律由本 mapper 填入，故遮蔽規則在此收斂為單一 choke point；觀看者身分取自
     * 當前 SecurityContext（與 {@code SecurityUtils.isAdmin()} 無參版、
     * {@code ArticleQueryService} 的 liked/bookmarked 填充同一慣例）。</p>
     */
    @Nested
    @DisplayName("rejectReason 可見性（內部審核評語僅作者與 ADMIN 可見）")
    class RejectReasonVisibility {

        private static final Long ARTICLE_AUTHOR_ID = 5L;
        private static final Long OTHER_USER_ID = 99L;

        @AfterEach
        void clearContext() {
            SecurityContextHolder.clearContext();
        }

        private void authenticateAs(Long userId, String springSecurityRole) {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    userId, null, List.of(new SimpleGrantedAuthority(springSecurityRole)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        private void authenticateAsAnonymousToken() {
            Authentication auth = new AnonymousAuthenticationToken(
                    "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        @Test
        @DisplayName("toResponse：無 SecurityContext（匿名）→ rejectReason 遮蔽為 null")
        void toResponse_anonymousViewer_masksRejectReason() {
            SecurityContextHolder.clearContext();
            Article article = article(UUID.randomUUID(), ARTICLE_AUTHOR_ID);

            assertThat(mapper.toResponse(article).getRejectReason()).isNull();
        }

        @Test
        @DisplayName("toResponse：AnonymousAuthenticationToken → rejectReason 遮蔽為 null")
        void toResponse_anonymousAuthenticationToken_masksRejectReason() {
            authenticateAsAnonymousToken();
            Article article = article(UUID.randomUUID(), ARTICLE_AUTHOR_ID);

            assertThat(mapper.toResponse(article).getRejectReason()).isNull();
        }

        @Test
        @DisplayName("toResponse：其他已登入的一般使用者 → rejectReason 遮蔽為 null")
        void toResponse_otherAuthenticatedViewer_masksRejectReason() {
            authenticateAs(OTHER_USER_ID, "ROLE_USER");
            Article article = article(UUID.randomUUID(), ARTICLE_AUTHOR_ID);

            assertThat(mapper.toResponse(article).getRejectReason()).isNull();
        }

        @Test
        @DisplayName("toResponse：作者本人 → 看得到 rejectReason")
        void toResponse_authorViewer_exposesRejectReason() {
            authenticateAs(ARTICLE_AUTHOR_ID, "ROLE_AUTHOR");
            Article article = article(UUID.randomUUID(), ARTICLE_AUTHOR_ID);

            assertThat(mapper.toResponse(article).getRejectReason()).isEqualTo("reason");
        }

        @Test
        @DisplayName("toResponse：ADMIN（非作者）→ 看得到 rejectReason")
        void toResponse_adminViewer_exposesRejectReason() {
            authenticateAs(OTHER_USER_ID, "ROLE_ADMIN");
            Article article = article(UUID.randomUUID(), ARTICLE_AUTHOR_ID);

            assertThat(mapper.toResponse(article).getRejectReason()).isEqualTo("reason");
        }

        @Test
        @DisplayName("toSummaryResponse：匿名 → rejectReason 遮蔽為 null")
        void toSummaryResponse_anonymousViewer_masksRejectReason() {
            SecurityContextHolder.clearContext();
            Article article = article(UUID.randomUUID(), ARTICLE_AUTHOR_ID);

            assertThat(mapper.toSummaryResponse(article, List.of()).getRejectReason()).isNull();
        }

        @Test
        @DisplayName("toSummaryResponse：作者本人 → 看得到 rejectReason（我的文章列表需要）")
        void toSummaryResponse_authorViewer_exposesRejectReason() {
            authenticateAs(ARTICLE_AUTHOR_ID, "ROLE_AUTHOR");
            Article article = article(UUID.randomUUID(), ARTICLE_AUTHOR_ID);

            assertThat(mapper.toSummaryResponse(article, List.of()).getRejectReason()).isEqualTo("reason");
        }

        @Test
        @DisplayName("toSummaryResponse：ADMIN → 看得到 rejectReason（待審列表需要）")
        void toSummaryResponse_adminViewer_exposesRejectReason() {
            authenticateAs(OTHER_USER_ID, "ROLE_ADMIN");
            Article article = article(UUID.randomUUID(), ARTICLE_AUTHOR_ID);

            assertThat(mapper.toSummaryResponse(article, List.of()).getRejectReason()).isEqualTo("reason");
        }

        @Test
        @DisplayName("toEditorResponse：匿名 → rejectReason 遮蔽為 null（縱深防禦，不倚賴上游守衛）")
        void toEditorResponse_anonymousViewer_masksRejectReason() {
            SecurityContextHolder.clearContext();
            Article article = article(UUID.randomUUID(), ARTICLE_AUTHOR_ID);

            assertThat(mapper.toEditorResponse(article).getRejectReason()).isNull();
        }

        @Test
        @DisplayName("toEditorResponse：作者本人 → 看得到 rejectReason（編輯器需顯示駁回理由）")
        void toEditorResponse_authorViewer_exposesRejectReason() {
            authenticateAs(ARTICLE_AUTHOR_ID, "ROLE_AUTHOR");
            Article article = article(UUID.randomUUID(), ARTICLE_AUTHOR_ID);

            assertThat(mapper.toEditorResponse(article).getRejectReason()).isEqualTo("reason");
        }
    }

    private Article article(UUID uuid, Long authorId) {
        LocalDateTime now = LocalDateTime.now();
        Article article = new Article();
        article.setId(100L);
        article.setUuid(uuid);
        article.setAuthorId(authorId);
        article.setTitle("Title");
        article.setSummary("summary");
        article.setCoverImageUrl("https://example.test/cover.png");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setSlug("slug");
        article.setLikeCount(10L);
        article.setCommentCount(5);
        article.setCreatedAt(now);
        article.setUpdatedAt(now);
        article.setPublishedAt(now);
        article.setRejectReason("reason");
        article.setSeriesPosition(2);
        return article;
    }
}
