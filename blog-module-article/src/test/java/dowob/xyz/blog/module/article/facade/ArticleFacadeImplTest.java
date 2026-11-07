package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreSource;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.module.article.model.ArticleSummaryRow;
import dowob.xyz.blog.module.article.model.ArticleTagRow;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.service.ArticleMarkdownRenderer;
import dowob.xyz.blog.module.article.service.ArticleTocCodec;
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ArticleFacadeImpl 單元測試
 *
 * <p>驗證各推薦查詢方法的邏輯與 Mapper 呼叫行為。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleFacadeImplTest {

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private UserFacade userFacade;

    @Mock
    private ArticleRecommendMapper recommendMapper;

    @Mock
    private ArticleService articleService;

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private TagFacade tagFacade;

    @Mock
    private ArticleEventPublisher articleEventPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    /** 還原流程在 article 內部渲染 Markdown：使用真實的純計算元件，才能斷言 HTML／TOC 與同一份 Markdown 的渲染結果一致 */
    private final ArticleMarkdownRenderer renderer = new ArticleMarkdownRenderer();

    private final ArticleTocCodec tocCodec = new ArticleTocCodec(new com.fasterxml.jackson.databind.ObjectMapper());

    private ArticleFacadeImpl facade;

    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    private static final Long ARTICLE_ID = 1L;

    /** 測試用作者公開 UUID（M6-a：文章直接存 author_uuid） */
    private static final UUID AUTHOR_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /** 測試用系列公開 UUID（M6-b：文章直接存 series_uuid） */
    private static final UUID SERIES_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(
            articleMapper, userFacade, recommendMapper, articleService,
            articleRepository, tagFacade, articleEventPublisher, transactionTemplate,
            new ArticleDataAssembler(),
            null, renderer, tocCodec
        , null);

        /** 推薦與索引查詢的作者暱稱一律經 UserFacade UUID 版批次解析（M6-a 起不再 JOIN users） */
        when(userFacade.getDisplayInfoByUuids(any())).thenReturn(
                java.util.Map.of(AUTHOR_UUID, new UserDisplayInfo(AUTHOR_UUID, "Author", null)));

        /** 讓 mock 的 TransactionTemplate 直接執行 callback，使受測方法主體照常運行 */
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    // ─── SP-B helper：建立測試用 Article entity ───
    private Article buildArticle(Long id, UUID uuid, ArticleStatus status, UUID seriesUuid, Integer pos) {
        Article a = new Article();
        a.setId(id);
        a.setUuid(uuid);
        a.setAuthorUuid(AUTHOR_UUID);
        a.setStatus(status);
        a.setSeriesUuid(seriesUuid);
        a.setSeriesPosition(pos);
        return a;
    }

    private ArticleSummaryRow row(Long id, UUID uuid, String title) {
        ArticleSummaryRow row = new ArticleSummaryRow();
        row.setId(id);
        row.setUuid(uuid.toString());
        row.setTitle(title);
        row.setSlug("slug");
        row.setSummary("summary");
        row.setAuthorUuid(AUTHOR_UUID);
        row.setViewCount(100L);
        row.setLikeCount(10L);
        row.setPublishedAt(LocalDateTime.now());
        return row;
    }

    @Nested
    class GetPublishedArticleBasicInfo {

        @Test
        @DisplayName("文章不存在時回傳 empty")
        void whenArticleNotFound_returnsEmpty() {
            when(recommendMapper.findPublishedIdByUuid(ARTICLE_UUID)).thenReturn(null);

            Optional<ArticleBasicInfo> result = facade.getPublishedArticleBasicInfo(ARTICLE_UUID);

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findTagIdsByArticleUuid(any(UUID.class));
        }

        @Test
        @DisplayName("文章存在時回傳基本資訊含標籤")
        void whenArticleExists_returnsBasicInfoWithTags() {
            UUID tag1 = UUID.randomUUID();
            UUID tag2 = UUID.randomUUID();
            when(recommendMapper.findPublishedIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_ID);
            when(recommendMapper.findTagIdsByArticleUuid(ARTICLE_UUID))
                    .thenReturn(List.of(tag1.toString(), tag2.toString()));

            Optional<ArticleBasicInfo> result = facade.getPublishedArticleBasicInfo(ARTICLE_UUID);

            assertThat(result).isPresent();
            assertThat(result.get().uuid()).isEqualTo(ARTICLE_UUID);
            assertThat(result.get().tagIds()).containsExactly(tag1, tag2);
        }
    }

    @Nested
    class GetArticlesByTagIds {

        @Test
        @DisplayName("tagIds 為空時不查詢直接回傳空列表")
        void whenTagIdsEmpty_skipsQueryAndReturnsEmptyList() {
            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(
                    Collections.emptyList(), ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findByTagIds(anyList(), any(UUID.class), anyInt());
        }

        @Test
        @DisplayName("tagIds 為 null 時不查詢直接回傳空列表")
        void whenTagIdsNull_skipsQueryAndReturnsEmptyList() {
            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(null, ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findByTagIds(anyList(), any(UUID.class), anyInt());
        }

        @Test
        @DisplayName("正常查詢並組裝標籤名稱")
        void queriesAndAssemblesTagNames() {
            UUID tagUuid = UUID.randomUUID();
            ArticleSummaryRow row = row(ARTICLE_ID, ARTICLE_UUID, "Test");
            ArticleTagRow tagRow = new ArticleTagRow();
            tagRow.setArticleUuid(ARTICLE_UUID.toString());
            tagRow.setTagName("Java");

            when(recommendMapper.findByTagIds(List.of(tagUuid.toString()), ARTICLE_UUID, 5))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleUuids(List.of(ARTICLE_UUID.toString())))
                    .thenReturn(List.of(tagRow));

            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(List.of(tagUuid), ARTICLE_UUID, 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Test");
            assertThat(result.get(0).tagNames()).containsExactly("Java");
        }

        @Test
        @DisplayName("查詢結果為空時回傳空列表")
        void whenMapperReturnsEmpty_returnsEmptyList() {
            UUID tagUuid = UUID.randomUUID();
            when(recommendMapper.findByTagIds(List.of(tagUuid.toString()), ARTICLE_UUID, 5))
                    .thenReturn(Collections.emptyList());

            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(List.of(tagUuid), ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
            verifyNoInteractions(userFacade);
            verify(recommendMapper, never()).findTagsByArticleUuids(anyList());
        }

        @Test
        @DisplayName("文章無對應標籤時 tagNames 回傳空列表")
        void whenNoTagsForArticle_tagNamesIsEmpty() {
            UUID tagUuid = UUID.randomUUID();
            ArticleSummaryRow row = row(ARTICLE_ID, ARTICLE_UUID, "No Tags");

            when(recommendMapper.findByTagIds(List.of(tagUuid.toString()), ARTICLE_UUID, 5))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleUuids(List.of(ARTICLE_UUID.toString())))
                    .thenReturn(Collections.emptyList());

            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(List.of(tagUuid), ARTICLE_UUID, 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).tagNames()).isEmpty();
        }
    }

    @Nested
    class GetPublishedArticlesByUuids {

        @Test
        @DisplayName("uuids 為空時不查詢直接回傳空列表")
        void whenUuidsEmpty_skipsQueryAndReturnsEmptyList() {
            List<ArticleSummaryInfo> result = facade.getPublishedArticlesByUuids(Collections.emptyList());

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findByUuids(anyList());
        }

        @Test
        @DisplayName("uuids 為 null 時不查詢直接回傳空列表")
        void whenUuidsNull_skipsQueryAndReturnsEmptyList() {
            List<ArticleSummaryInfo> result = facade.getPublishedArticlesByUuids(null);

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findByUuids(anyList());
        }

        @Test
        @DisplayName("正常查詢並組裝標籤名稱")
        void queriesAndAssemblesTagNames() {
            ArticleSummaryRow row = row(ARTICLE_ID, ARTICLE_UUID, "By UUID");
            ArticleTagRow tagRow = new ArticleTagRow();
            tagRow.setArticleUuid(ARTICLE_UUID.toString());
            tagRow.setTagName("Spring");

            when(recommendMapper.findByUuids(List.of(ARTICLE_UUID)))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleUuids(List.of(ARTICLE_UUID.toString())))
                    .thenReturn(List.of(tagRow));

            List<ArticleSummaryInfo> result = facade.getPublishedArticlesByUuids(List.of(ARTICLE_UUID));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("By UUID");
            assertThat(result.get(0).tagNames()).containsExactly("Spring");
        }
    }

    @Nested
    class GetRecentPublishedArticles {

        @Test
        @DisplayName("正常查詢並組裝結果")
        void queriesAndAssemblesResults() {
            ArticleSummaryRow row = row(ARTICLE_ID, ARTICLE_UUID, "Recent");
            ArticleTagRow tagRow = new ArticleTagRow();
            tagRow.setArticleUuid(ARTICLE_UUID.toString());
            tagRow.setTagName("Java");

            when(recommendMapper.findRecentPublished(ARTICLE_UUID, 5))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleUuids(List.of(ARTICLE_UUID.toString())))
                    .thenReturn(List.of(tagRow));

            List<ArticleSummaryInfo> result = facade.getRecentPublishedArticles(ARTICLE_UUID, 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Recent");
            assertThat(result.get(0).tagNames()).containsExactly("Java");
        }
    }

    /** 建立索引用的已發布文章（只填索引欄位） */
    private static Article indexArticle(UUID uuid, UUID authorUuid, String content) {
        Article article = new Article();
        article.setUuid(uuid);
        article.setAuthorUuid(authorUuid);
        article.setTitle("t-" + uuid);
        article.setSlug("s-" + uuid);
        article.setSummary("summary");
        article.setContent(content);
        article.setStatus(ArticleStatus.PUBLISHED);
        return article;
    }

    /** 建立批次標籤查詢的結果列 */
    private static dowob.xyz.blog.module.article.model.TagWithArticleUuid tagRow(UUID articleUuid, String name) {
        dowob.xyz.blog.module.article.model.TagWithArticleUuid row = new dowob.xyz.blog.module.article.model.TagWithArticleUuid();
        row.setId(UUID.nameUUIDFromBytes(name.getBytes()));
        row.setName(name);
        row.setSlug(name.toLowerCase());
        row.setArticleUuid(articleUuid);
        return row;
    }

    /**
     * keyset 分批讀取索引資料（PERF-01）：取代原本一次撈全站的 findAllPublishedForIndex（已移除；
     * 其「正確組裝／無文章回空／content 為 null 回空字串／查無作者暱稱為 null」四個案例併入本群組），
     * 並把「每篇各發一次 findTagsByArticleUuid」的 N+1 改成每批一次批次查詢。
     */
    @Nested
    @DisplayName("findPublishedForIndexAfter（keyset 分批讀取）")
    class FindPublishedForIndexAfter {

        @Test
        @DisplayName("以游標與上限呼叫 mapper，標籤依文章分組、作者暱稱與 authorUuid 正確組裝，順序維持 mapper 的 uuid 升冪")
        void assemblesIndexDataFromBatchQueries() {
            UUID cursor = UUID.randomUUID();
            UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
            when(articleMapper.findPublishedForIndexAfter(cursor, 2)).thenReturn(List.of(
                    indexArticle(first, AUTHOR_UUID, "**正文一**"), indexArticle(second, AUTHOR_UUID, "正文二")));
            when(articleMapper.findTagsByArticleUuids(List.of(first, second))).thenReturn(List.of(
                    tagRow(first, "Java"), tagRow(first, "Spring"), tagRow(second, "Go")));

            List<ArticleIndexData> result = facade.findPublishedForIndexAfter(cursor, 2);

            assertThat(result).extracting(ArticleIndexData::articleUuid).containsExactly(first, second);
            assertThat(result.get(0).tags()).extracting(ArticleIndexData.TagData::name).containsExactly("Java", "Spring");
            assertThat(result.get(1).tags()).extracting(ArticleIndexData.TagData::name).containsExactly("Go");
            assertThat(result.get(0).contentText()).isEqualTo("正文一");
            assertThat(result).extracting(ArticleIndexData::authorUuid).containsOnly(AUTHOR_UUID);
            assertThat(result).extracting(ArticleIndexData::authorNickname).containsOnly("Author");
        }

        @Test
        @DisplayName("一批只發一次標籤批次查詢、一次 UserFacade 查詢；不再逐篇呼叫 findTagsByArticleUuid（不 N+1）")
        void oneTagQueryAndOneUserQueryPerBatch() {
            List<Article> batch = java.util.stream.IntStream.range(0, 5)
                    .mapToObj(i -> indexArticle(UUID.randomUUID(), AUTHOR_UUID, "c")).toList();
            when(articleMapper.findPublishedForIndexAfter(null, 200)).thenReturn(batch);
            when(articleMapper.findTagsByArticleUuids(anyList())).thenReturn(List.of());

            facade.findPublishedForIndexAfter(null, 200);

            verify(articleMapper, times(1)).findTagsByArticleUuids(anyList());
            verify(articleMapper, never()).findTagsByArticleUuid(any());
            verify(userFacade, times(1)).getDisplayInfoByUuids(any());
        }

        @Test
        @DisplayName("連續讀三批：標籤查詢次數等於批數（3），與文章數無關")
        void tagQueriesEqualBatchCount() {
            UUID last1 = UUID.randomUUID();
            UUID last2 = UUID.randomUUID();
            when(articleMapper.findPublishedForIndexAfter(null, 2)).thenReturn(List.of(
                    indexArticle(UUID.randomUUID(), AUTHOR_UUID, "c"), indexArticle(last1, AUTHOR_UUID, "c")));
            when(articleMapper.findPublishedForIndexAfter(last1, 2)).thenReturn(List.of(
                    indexArticle(UUID.randomUUID(), AUTHOR_UUID, "c"), indexArticle(last2, AUTHOR_UUID, "c")));
            when(articleMapper.findPublishedForIndexAfter(last2, 2)).thenReturn(List.of(
                    indexArticle(UUID.randomUUID(), AUTHOR_UUID, "c")));
            when(articleMapper.findTagsByArticleUuids(anyList())).thenReturn(List.of());

            UUID cursor = null;
            int batches = 0;
            while (true) {
                List<ArticleIndexData> page = facade.findPublishedForIndexAfter(cursor, 2);
                batches++;
                if (page.size() < 2) {
                    break;
                }
                cursor = page.get(page.size() - 1).articleUuid();
            }

            assertThat(batches).isEqualTo(3);
            verify(articleMapper, times(3)).findTagsByArticleUuids(anyList());
            verify(articleMapper, never()).findTagsByArticleUuid(any());
        }

        @Test
        @DisplayName("沒有文章時不查標籤、不查使用者")
        void emptyBatch_skipsTagAndUserQueries() {
            when(articleMapper.findPublishedForIndexAfter(null, 200)).thenReturn(List.of());

            assertThat(facade.findPublishedForIndexAfter(null, 200)).isEmpty();
            verify(articleMapper, never()).findTagsByArticleUuids(anyList());
            verifyNoInteractions(userFacade);
        }

        @Test
        @DisplayName("limit 超出 1..INDEX_BATCH_MAX_SIZE 時拋 IllegalArgumentException，且不查 DB（界限寫進程式）")
        void limitOutOfRange_throwsWithoutQuerying() {
            assertThatThrownBy(() -> facade.findPublishedForIndexAfter(null, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> facade.findPublishedForIndexAfter(null,
                    dowob.xyz.blog.infrastructure.facade.ArticleFacade.INDEX_BATCH_MAX_SIZE + 1))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(articleMapper);
        }

        @Test
        @DisplayName("content 為 null 時 contentText 為空字串；沒有標籤的文章 tags 為空清單")
        void nullContentAndNoTags() {
            UUID uuid = UUID.randomUUID();
            when(articleMapper.findPublishedForIndexAfter(null, 10)).thenReturn(List.of(indexArticle(uuid, AUTHOR_UUID, null)));
            when(articleMapper.findTagsByArticleUuids(anyList())).thenReturn(List.of());

            ArticleIndexData data = facade.findPublishedForIndexAfter(null, 10).get(0);

            assertThat(data.contentText()).isEmpty();
            assertThat(data.tags()).isEmpty();
        }

        @Test
        @DisplayName("UserFacade 查無作者時 authorNickname 為 null，authorUuid 仍取自文章")
        void unknownAuthor_nicknameNull() {
            UUID unknownAuthor = UUID.randomUUID();
            when(articleMapper.findPublishedForIndexAfter(null, 10))
                    .thenReturn(List.of(indexArticle(UUID.randomUUID(), unknownAuthor, "c")));
            when(articleMapper.findTagsByArticleUuids(anyList())).thenReturn(List.of());
            when(userFacade.getDisplayInfoByUuids(any())).thenReturn(java.util.Map.of());

            ArticleIndexData data = facade.findPublishedForIndexAfter(null, 10).get(0);

            assertThat(data.authorNickname()).isNull();
            assertThat(data.authorUuid()).isEqualTo(unknownAuthor);
        }
    }

    @Nested
    @DisplayName("findPublishedForIndexByUuids（重建期間的增量補齊）")
    class FindPublishedForIndexByUuids {

        @Test
        @DisplayName("null 或空集合回傳空清單，不查 DB")
        void nullOrEmpty_returnsEmptyWithoutQuery() {
            assertThat(facade.findPublishedForIndexByUuids(null)).isEmpty();
            assertThat(facade.findPublishedForIndexByUuids(List.of())).isEmpty();
            verifyNoInteractions(articleMapper);
        }

        @Test
        @DisplayName("去除 null 與重複後查詢；只回傳 mapper 認定仍為 PUBLISHED 者，並帶標籤與作者暱稱")
        void returnsOnlyPublishedWithTagsAndAuthor() {
            UUID published = UUID.randomUUID();
            UUID archived = UUID.randomUUID();
            List<UUID> input = new ArrayList<>(List.of(published, archived, published));
            input.add(null);
            when(articleMapper.findPublishedForIndexByUuids(List.of(published, archived)))
                    .thenReturn(List.of(indexArticle(published, AUTHOR_UUID, "c")));
            when(articleMapper.findTagsByArticleUuids(List.of(published))).thenReturn(List.of(tagRow(published, "Java")));

            List<ArticleIndexData> result = facade.findPublishedForIndexByUuids(input);

            assertThat(result).singleElement().satisfies(d -> {
                assertThat(d.articleUuid()).isEqualTo(published);
                assertThat(d.tags()).extracting(ArticleIndexData.TagData::name).containsExactly("Java");
                assertThat(d.authorNickname()).isEqualTo("Author");
            });
        }

        @Test
        @DisplayName("全部都已不是 PUBLISHED：回傳空清單，不查標籤與使用者")
        void noneStillPublished_returnsEmpty() {
            when(articleMapper.findPublishedForIndexByUuids(anyList())).thenReturn(List.of());

            assertThat(facade.findPublishedForIndexByUuids(List.of(UUID.randomUUID()))).isEmpty();
            verify(articleMapper, never()).findTagsByArticleUuids(anyList());
            verifyNoInteractions(userFacade);
        }
    }

    @Test
    @DisplayName("countPublished 委派 mapper")
    void countPublished_delegatesToMapper() {
        when(articleMapper.countPublished()).thenReturn(42L);

        assertThat(facade.countPublished()).isEqualTo(42L);
    }

    /**
     * 幽靈 document 清除的「即時查證」查詢。
     *
     * <p>search 模組掃到 ES 裡的 document id 後，必須回 DB 問「這些現在還是 PUBLISHED 嗎」，
     * 而不是拿重建當下的快照來比對——否則快照撈完之後才發布的文章會被當成幽靈刪掉。
     * 判準必須即時，且 articles 是業務 Data，跨模組只能走本 facade（architecture.md）。</p>
     */
    @Nested
    @DisplayName("filterPublishedUuids（幽靈清除的即時查證）")
    class FilterPublishedUuids {

        @Test
        @DisplayName("只回傳目前確實是 PUBLISHED 的 uuid")
        void returnsOnlyCurrentlyPublishedUuids() {
            UUID published = UUID.randomUUID();
            UUID archived = UUID.randomUUID();
            when(articleMapper.findPublishedUuidsIn(List.of(published, archived)))
                    .thenReturn(List.of(published.toString()));

            Set<UUID> result = facade.filterPublishedUuids(List.of(published, archived));

            assertThat(result).containsExactly(published);
        }

        @Test
        @DisplayName("輸入為空時不查 DB（避免 IN () 語法錯誤）")
        void whenEmptyInput_doesNotQueryDatabase() {
            assertThat(facade.filterPublishedUuids(List.of())).isEmpty();
            assertThat(facade.filterPublishedUuids(null)).isEmpty();

            verify(articleMapper, never()).findPublishedUuidsIn(any());
        }

        /**
         * ES 掃描是分頁的，單次回查的 id 數等於一頁的大小；一次全塞進 {@code IN (...)}
         * 會產生超長 SQL 與過多 bind 參數，故 facade 內部再切批。
         */
        @Test
        @DisplayName("超過批次上限時分批查詢，每批不超過上限")
        void whenInputExceedsBatchSize_splitsIntoBatches() {
            List<UUID> uuids = new ArrayList<>();
            for (int i = 0; i < 1200; i++) {
                uuids.add(UUID.randomUUID());
            }
            when(articleMapper.findPublishedUuidsIn(any())).thenReturn(List.of());

            facade.filterPublishedUuids(uuids);

            ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
            verify(articleMapper, times(3)).findPublishedUuidsIn(captor.capture());
            assertThat(captor.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(500));
            assertThat(captor.getAllValues().stream().mapToInt(List::size).sum()).isEqualTo(1200);
        }
    }

    @Nested
    class GetArticlesPublishedAfter {

        @Test
        @DisplayName("起始時間原樣交給 Mapper（不被截斷或位移），結果不過濾、不重排，也不碰 UserFacade／ArticleService")
        void passesBoundaryUnchanged_andPreservesMapperRows() {
            LocalDateTime since = LocalDateTime.of(2026, 11, 6, 12, 34, 56, 789_000_000);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            ArticleTrendingData older = new ArticleTrendingData(first, 1L, 0L, since.plusMinutes(1));
            ArticleTrendingData newer = new ArticleTrendingData(second, 999L, 9L, since.plusDays(3));
            when(recommendMapper.findPublishedAfter(since)).thenReturn(List.of(older, newer));

            List<ArticleTrendingData> result = facade.getArticlesPublishedAfter(since);

            verify(recommendMapper, times(1)).findPublishedAfter(since);
            assertThat(result).as("不過濾、不重排（排序由 SQL 決定）").containsExactly(older, newer);
            verifyNoInteractions(userFacade, articleService);
        }
    }

    // ─── read method delegate + Article→ArticleData 轉換 ───

    @Nested
    @DisplayName("findByUuid")
    class FindByUuidDelegate {

        @Test
        @DisplayName("文章存在時回傳 ArticleData（含 Article→ArticleData 轉換驗證）")
        void returnsArticleData_convertedFromEntity() {
            UUID uuid = UUID.randomUUID();
            Article a = buildArticle(100L, uuid, ArticleStatus.PUBLISHED, SERIES_UUID, 3);
            when(articleService.findByUuid(uuid)).thenReturn(Optional.of(a));

            Optional<ArticleData> result = facade.findByUuid(uuid);

            assertThat(result).isPresent();
            ArticleData data = result.get();
            assertThat(data.uuid()).isEqualTo(uuid);
            assertThat(data.status()).isEqualTo("PUBLISHED");
            assertThat(data.seriesUuid()).isEqualTo(SERIES_UUID);
            assertThat(data.seriesPosition()).isEqualTo(3);
        }

        @Test
        @DisplayName("無系列的草稿 → ArticleData 的 seriesUuid / seriesPosition 為 null")
        void draftWithoutSeries_returnsNullSeriesFields() {
            UUID uuid = UUID.randomUUID();
            Article a = buildArticle(100L, uuid, ArticleStatus.DRAFT, null, null);
            when(articleService.findByUuid(uuid)).thenReturn(Optional.of(a));

            Optional<ArticleData> result = facade.findByUuid(uuid);

            assertThat(result).isPresent();
            assertThat(result.get().status()).isEqualTo("DRAFT");
            assertThat(result.get().seriesUuid()).isNull();
            assertThat(result.get().seriesPosition()).isNull();
        }

        @Test
        @DisplayName("文章不存在時回傳 empty Optional")
        void emptyArticle_returnsEmptyOptional() {
            UUID uuid = UUID.randomUUID();
            when(articleService.findByUuid(uuid)).thenReturn(Optional.empty());

            Optional<ArticleData> result = facade.findByUuid(uuid);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findBySeriesUuidOrderByPosition")
    class FindBySeriesUuidOrderByPositionDelegate {

        @Test
        @DisplayName("回傳 ArticleData 列表（含 seriesPosition 驗證）")
        void returnsArticleDataList() {
            Article a1 = buildArticle(1L, UUID.randomUUID(), ArticleStatus.PUBLISHED, SERIES_UUID, 1);
            Article a2 = buildArticle(2L, UUID.randomUUID(), ArticleStatus.PUBLISHED, SERIES_UUID, 2);
            when(articleService.findBySeriesUuidOrderByPosition(SERIES_UUID)).thenReturn(List.of(a1, a2));

            List<ArticleData> result = facade.findBySeriesUuidOrderByPosition(SERIES_UUID);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).seriesPosition()).isEqualTo(1);
            assertThat(result.get(1).seriesPosition()).isEqualTo(2);
        }
    }

    // ─── findContentByUuid + applyRestoreContent ───

    @Nested
    @DisplayName("findContentByUuid")
    class FindContentById {

        @Test
        @DisplayName("article 存在 → return Optional 含 ArticleContentData")
        void findContentByUuid_existing_returnsContentData() {
            // given
            Long articleId = 100L;
            UUID articleUuid = UUID.randomUUID();
            Article article = new Article();
            article.setId(articleId);
            article.setUuid(articleUuid);
            article.setAuthorUuid(AUTHOR_UUID);
            article.setTitle("Test Title");
            article.setSlug("test-slug");
            article.setContent("# Hello");
            article.setSummary("summary");
            article.setCoverImageUrl("https://cdn.example/cover.jpg");
            article.setStatus(ArticleStatus.PUBLISHED);
            when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

            // when
            Optional<ArticleContentData> result = facade.findContentByUuid(articleUuid);

            // then
            assertThat(result).isPresent();
            ArticleContentData data = result.get();
            assertThat(data.uuid()).isEqualTo(articleUuid);
            assertThat(data.title()).isEqualTo("Test Title");
            assertThat(data.slug()).isEqualTo("test-slug");
            assertThat(data.content()).isEqualTo("# Hello");
            assertThat(data.summary()).isEqualTo("summary");
            assertThat(data.coverImageUrl()).isEqualTo("https://cdn.example/cover.jpg");
            assertThat(data.status()).isEqualTo("PUBLISHED");
            assertThat(data.authorUuid()).isEqualTo(AUTHOR_UUID);
        }

        @Test
        @DisplayName("article 不存在 → return Optional.empty")
        void findContentByUuid_notFound_returnsEmpty() {
            UUID missing = UUID.randomUUID();
            when(articleService.findByUuid(missing)).thenReturn(Optional.empty());

            Optional<ArticleContentData> result = facade.findContentByUuid(missing);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("article.status 為 null → ArticleContentData.status 為 null")
        void findContentByUuid_statusNull_statusFieldNull() {
            Article article = new Article();
            article.setId(100L);
            UUID uuid = UUID.randomUUID();
            article.setUuid(uuid);
            article.setAuthorUuid(AUTHOR_UUID);
            article.setStatus(null);
            when(articleService.findByUuid(uuid)).thenReturn(Optional.of(article));

            Optional<ArticleContentData> result = facade.findContentByUuid(uuid);

            assertThat(result).isPresent();
            assertThat(result.get().status()).isNull();
        }
    }

    @Nested
    @DisplayName("applyRestoreContent atomic")
    class ApplyRestoreContent {

        private Long articleId;
        private UUID articleUuid;
        private Article existing;

        @BeforeEach
        void setUpArticle() {
            articleId = 100L;
            articleUuid = UUID.randomUUID();
            existing = new Article();
            existing.setId(articleId);
            existing.setUuid(articleUuid);
            existing.setStatus(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(articleUuid)).thenReturn(Optional.of(existing));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("article 不存在 → throw ARTICLE_NOT_FOUND（code A0201）")
        void applyRestoreContent_articleNotFound_throws() {
            UUID missing = UUID.randomUUID();
            when(articleRepository.findByUuid(missing)).thenReturn(Optional.empty());
            ArticleRestoreSource data = new ArticleRestoreSource(
                "T", "s", "c", "sum", null, List.of()
            );

            assertThatThrownBy(() -> facade.applyRestoreContent(missing, data))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo("A0201");

            verify(articleRepository, never()).save(any(Article.class));
            verifyNoInteractions(tagFacade, articleEventPublisher);
        }

        @Test
        @DisplayName("DRAFT article 還原 → 7 個內容欄位全數寫回 + syncArticleTags + ContentChanged(RESTORED)")
        void applyRestoreContent_draftArticle_restoresAllContentFields() {
            /*
             * F-H1 後：PUBLISHED 已被內容凍結守衛擋在本方法之外（見 ContentFreezeGuard），
             * 「完整欄位還原」的正常案例改以 DRAFT 表達，PUBLISHED 專屬的 publishUpdated 斷言隨之移除。
             * 事件判準本身（文章現況為 PUBLISHED 才發 publishUpdated）維持不變，只是已無呼叫端能到達該分支。
             */
            existing.setStatus(ArticleStatus.DRAFT);
            ArticleRestoreSource data = new ArticleRestoreSource("New Title", "new-slug", "# New", "New summary", "https://cdn/new.jpg", List.of(UUID.randomUUID(), UUID.randomUUID()));

            facade.applyRestoreContent(articleUuid, data);

            assertThat(existing.getTitle()).isEqualTo("New Title");
            assertThat(existing.getSlug()).isEqualTo("new-slug");
            assertThat(existing.getContent()).isEqualTo("# New");
            assertThat(existing.getSummary()).isEqualTo("New summary");
            assertThat(existing.getCoverImageUrl()).isEqualTo("https://cdn/new.jpg");
            assertThat(existing.getStatus()).isEqualTo(ArticleStatus.DRAFT);
            assertThat(existing.getContentHtml())
                .as("HTML 由 article 內部渲染，等於對同一份 Markdown 呼叫 renderer 的結果")
                .isEqualTo(renderer.render("# New").html());

            verify(articleRepository).save(existing);
            verify(tagFacade).syncArticleTags(eq(articleUuid), eq(data.tags()));
            verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
            verify(articleEventPublisher, never()).publishUpdated(any());
        }

        @Test
        @DisplayName("SEC-25：還原內容超過 200,000 字元（上限施行前留下的舊快照）→ 拒絕，entity 與事件都不動")
        void applyRestoreContent_contentOverLimit_rejectedWithoutMutation() {
            ArticleRestoreSource data = new ArticleRestoreSource(
                "T", "s", "a".repeat(200_001), "sum", null, List.of());

            assertThatThrownBy(() -> facade.applyRestoreContent(articleUuid, data))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo("A0001");

            assertThat(existing.getContent()).isNull();
            verify(articleRepository, never()).save(any(Article.class));
            verifyNoInteractions(tagFacade, articleEventPublisher);
        }

        @Test
        @DisplayName("SEC-25：還原內容剛好 200,000 字元 → 通過")
        void applyRestoreContent_contentAtLimit_accepted() {
            ArticleRestoreSource data = new ArticleRestoreSource(
                "T", "s", "a".repeat(200_000), "sum", null, List.of());

            facade.applyRestoreContent(articleUuid, data);

            assertThat(existing.getContent()).hasSize(200_000);
        }

        @Test
        @DisplayName("DRAFT article 還原 → 只發 publishContentChanged，不發 publishUpdated")
        void applyRestoreContent_draftArticle_publishesOnlyContentChanged() {
            ArticleRestoreSource data = new ArticleRestoreSource("T", "s", "c", "sum", null, List.of());

            facade.applyRestoreContent(articleUuid, data);

            assertThat(existing.getStatus()).isEqualTo(ArticleStatus.DRAFT);
            verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
            verify(articleEventPublisher, never()).publishUpdated(any());
        }

        @Test
        @DisplayName("還原不改動 article.status（SEC-02：還原資料已不含 status）")
        void applyRestoreContent_doesNotChangeStatus() {
            ArticleStatus before = existing.getStatus();
            ArticleRestoreSource data = new ArticleRestoreSource("T", "s", "c", "sum", null, List.of());

            facade.applyRestoreContent(articleUuid, data);

            assertThat(existing.getStatus()).isEqualTo(before);
        }

        /**
         * 版本還原曾是唯一繞過 {@code validateStatusTransition} 的狀態轉換路徑：REJECTED 的文章
         * 只要還原一份舊的 PUBLISHED 快照就會直接變成 PUBLISHED，帶著 admin 寫的內部駁回評語
         * 進入匿名可讀的公開狀態。SEC-02 讓 {@code ArticleRestoreSource} 不帶 status 欄位而堵住此路，
         * 本測試從「內部評語不得外流」的角度把該保證釘住：還原不得改狀態，rejectReason 也不受影響。
         */
        @Test
        @DisplayName("REJECTED 文章還原 → 狀態與 rejectReason 皆不動（還原不得成為駁回文章洗白成公開的路徑）")
        void applyRestoreContent_rejectedArticle_keepsStatusAndRejectReason() {
            existing.setStatus(ArticleStatus.REJECTED);
            existing.setRejectReason("內部審核評語：抄襲疑慮，勿對外");
            ArticleRestoreSource data = new ArticleRestoreSource("T", "s", "c", "sum", null, List.of());

            facade.applyRestoreContent(articleUuid, data);

            assertThat(existing.getStatus()).isEqualTo(ArticleStatus.REJECTED);
            assertThat(existing.getRejectReason()).isEqualTo("內部審核評語：抄襲疑慮，勿對外");
            verify(articleEventPublisher, never()).publishUpdated(any());
        }

        @Test
        @DisplayName("tags 為 null → syncArticleTags 用空清單")
        void applyRestoreContent_tagsNull_syncWithEmptyList() {
            ArticleRestoreSource data = new ArticleRestoreSource("T", "s", "c", "sum", null, null);

            facade.applyRestoreContent(articleUuid, data);

            verify(tagFacade).syncArticleTags(articleUuid, List.of());
        }

        @Test
        @DisplayName("toc 一併還原：article.toc 應更新為對還原內容渲染出的 TOC，不可停留在還原前的舊 TOC")
        void applyRestoreContent_setsTocFromRenderedContent() {
            existing.setToc("[{\"id\":\"heading-舊章節\",\"text\":\"舊章節\",\"level\":2}]");
            String restoredToc = tocCodec.serialize(renderer.render("## 新章節").toc());
            ArticleRestoreSource data = new ArticleRestoreSource("T", "s", "## 新章節", "sum", null, List.of());

            facade.applyRestoreContent(articleUuid, data);

            assertThat(existing.getToc()).isEqualTo(restoredToc);
            assertThat(existing.getToc()).as("渲染結果確實含新章節").contains("新章節").doesNotContain("舊章節");
        }

        @Test
        @DisplayName("還原內容無標題 → toc 為空 JSON 陣列，舊 TOC 被清掉（維持 articles.toc 恆為合法陣列的不變量）")
        void applyRestoreContent_contentWithoutHeadings_storesEmptyJsonArray() {
            existing.setToc("[{\"id\":\"heading-舊章節\",\"text\":\"舊章節\",\"level\":2}]");
            ArticleRestoreSource data = new ArticleRestoreSource("T", "s", "c", "sum", null, List.of());

            facade.applyRestoreContent(articleUuid, data);

            assertThat(existing.getToc()).isEqualTo("[]");
        }

        @Test
        @DisplayName("TOC 序列化結果為 null（防禦分支）→ 存空 JSON 陣列，不得把 null 寫進 articles.toc")
        void applyRestoreContent_serializedTocNull_storesEmptyJsonArray() {
            ArticleTocCodec nullCodec = org.mockito.Mockito.mock(ArticleTocCodec.class);
            when(nullCodec.serialize(any())).thenReturn(null);
            ArticleFacadeImpl facadeWithNullToc = new ArticleFacadeImpl(
                articleMapper, userFacade, recommendMapper, articleService,
                articleRepository, tagFacade, articleEventPublisher, transactionTemplate,
                new ArticleDataAssembler(),
                null, renderer, nullCodec
            , null);
            existing.setToc("[{\"id\":\"heading-舊章節\",\"text\":\"舊章節\",\"level\":2}]");
            ArticleRestoreSource data = new ArticleRestoreSource("T", "s", "## 新章節", "sum", null, List.of());

            facadeWithNullToc.applyRestoreContent(articleUuid, data);

            assertThat(existing.getToc()).isEqualTo("[]");
        }

        @Test
        @DisplayName("invocation 順序：save → syncArticleTags → publishEvents")
        void applyRestoreContent_invocationOrder_saveThenSyncTagsThenPublish() {
            /*
             * F-H1 後 PUBLISHED 已被內容凍結守衛擋下，順序驗證改以 DRAFT 為前提；
             * publishUpdated 不再出現在此鏈上（該分支已無呼叫端能到達）。
             */
            existing.setStatus(ArticleStatus.DRAFT);
            ArticleRestoreSource data = new ArticleRestoreSource("T", "s", "c", "sum", null, List.of());

            facade.applyRestoreContent(articleUuid, data);

            InOrder inOrder = inOrder(articleRepository, tagFacade, articleEventPublisher);
            inOrder.verify(articleRepository).save(existing);
            inOrder.verify(tagFacade).syncArticleTags(eq(articleUuid), anyList());
            inOrder.verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
        }

        /**
         * 還原後的檔案綁定改由 file 模組消費 RESTORED 事件回填（W5A／Q12，Yuan 2026-11-05 決定）。
         *
         * <p>原本（修復「還原後圖片破圖」）本方法在 commit 後同步呼叫 FileFacade；現在 article 模組完全不碰 file 模組，
         * 只需保證仍發出 RESTORED 事件（回填的觸發來源）。解析與回填的測試見 file 模組。</p>
         */
        @Nested
        @DisplayName("還原後檔案綁定改由 file 模組消費 RESTORED 事件（W5A／Q12）")
        class RestoreFileBindingViaEventTests {

            @Test
            @DisplayName("還原內容含圖 → 發出 RESTORED 事件（file 模組據此非同步回填綁定）")
            void applyRestoreContent_contentWithImages_publishesRestoredForAsyncBinding() {
                ArticleRestoreSource data = new ArticleRestoreSource("T", "s",
                        "![img](/api/v1/files/" + UUID.randomUUID() + "/content)", "sum", null, List.of());

                facade.applyRestoreContent(articleUuid, data);

                verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
            }
        }

        /**
         * SEC-02（HIGH）：版本還原路徑繞過文章狀態守衛。
         *
         * <p><strong>攻擊面（修復前）</strong>：還原資料曾帶著快照當時的 status，
         * {@code applyRestoreContent} 直接 {@code setStatus} 套回去。作者拿一份舊的 PUBLISHED 快照
         * 按「還原」，就能把被 ADMIN 駁回（REJECTED）或送審中（PENDING_REVIEW）的文章直接變回
         * PUBLISHED，繞過 {@code ArticleCommandSubService.VALID_TRANSITIONS} 與
         * 「PENDING_REVIEW → PUBLISHED 僅 ADMIN」的限制。</p>
         *
         * <p><strong>修法（Yuan 拍板）</strong>：還原只還原內容，狀態一律不動——不是把還原接進守衛，
         * 而是移除還原改動狀態的能力：還原資料連 status 欄位都沒有，讓這條路徑
         * 在型別上就無法改狀態。</p>
         *
         * <p><strong>F-H1 後的涵蓋範圍</strong>：PENDING_REVIEW / PUBLISHED / ARCHIVED 三種狀態
         * 已被內容凍結守衛擋在本方法之外，原本「狀態仍為 X」的三個案例升級為
         * 「整個操作被拒絕、內容與狀態都不動」，移到 {@link ContentFreezeGuard}（斷言更強）。
         * 本類別只保留仍能進到寫入路徑的 DRAFT / REJECTED。</p>
         *
         * <p>下表以「文章現況」為變因（快照狀態已無從傳入，這正是修法的重點）：</p>
         * <pre>
         * # | 文章現況  | 預期
         * 1 | REJECTED | 仍為 REJECTED（不得繞過審核）
         * 2 | DRAFT    | 仍為 DRAFT，且不發 publishUpdated（非公開文章不得被重新索引）
         * </pre>
         */
        @Nested
        @DisplayName("SEC-02 狀態守衛：還原只還原內容，不得改動文章狀態")
        class RestoreNeverChangesStatus {

            @Test
            @DisplayName("REJECTED 文章還原 → 狀態仍為 REJECTED（不得繞過審核）")
            void applyRestoreContent_rejectedArticle_keepsRejectedStatus() {
                existing.setStatus(ArticleStatus.REJECTED);
                ArticleRestoreSource data = new ArticleRestoreSource("T", "s", "c", "sum", null, List.of());

                facade.applyRestoreContent(articleUuid, data);

                assertThat(existing.getStatus())
                        .as("還原不得改動文章狀態，否則可繞過 PENDING_REVIEW → PUBLISHED 僅 ADMIN 的限制")
                        .isEqualTo(ArticleStatus.REJECTED);
                assertThat(existing.getContent())
                        .as("內容仍必須被還原")
                        .isEqualTo("c");
            }

            @Test
            @DisplayName("DRAFT 文章還原 → 狀態仍為 DRAFT，且不發 publishUpdated（非公開文章不得被重新索引）")
            void applyRestoreContent_draftArticle_keepsDraftAndDoesNotPublishUpdated() {
                existing.setStatus(ArticleStatus.DRAFT);
                ArticleRestoreSource data = new ArticleRestoreSource("T", "s", "c", "sum", null, List.of());

                facade.applyRestoreContent(articleUuid, data);

                assertThat(existing.getStatus()).isEqualTo(ArticleStatus.DRAFT);
                verify(articleEventPublisher).publishContentChanged(existing,
                        ArticleContentChangedEvent.Action.RESTORED);
                verify(articleEventPublisher, never()).publishUpdated(any());
            }
        }

        /**
         * F-H1（HIGH）：版本還原繞過內容凍結守衛，構成審核 TOCTOU。
         *
         * <p><strong>攻擊面（修復前）</strong>：{@code ArticleCommandSubService#updateArticle}
         * 規定 PENDING_REVIEW / PUBLISHED / ARCHIVED 內容凍結（PUT 會被 A0209 擋下），
         * 但還原路徑完全不受此約束——作者送審後仍能用還原把內容換成另一個版本，
         * admin 審的是 A、通過的是 B。SEC-02（PR #66）把 status 從還原路徑移除後，
         * 換內容在狀態機上完全無痕，反而更隱蔽。</p>
         *
         * <p><strong>修法</strong>：凍結判斷抽成 {@code ArticleContentFreezePolicy}（唯一真相），
         * PUT 與還原兩條寫入路徑共用；守衛落在 article 模組自己的寫入方法內、mutate 之前，
         * 任何呼叫端（含未來新增的）都繞不過去，而不是要求 version 模組記得先問。</p>
         *
         * <pre>
         * # | 文章現況        | 預期
         * 1 | PENDING_REVIEW | A0209，且完全不寫入、不同步標籤、不發事件、不動檔案綁定
         * 2 | PUBLISHED      | 同上
         * 3 | ARCHIVED       | 同上
         * 4 | DRAFT          | 還原成功（正常用途，不得被守衛誤殺）
         * 5 | REJECTED       | 還原成功（被駁回後改稿重送正是還原的用途）
         * </pre>
         */
        @Nested
        @DisplayName("F-H1 內容凍結守衛：審核中／已發布／已封存不得以還原改寫內容")
        class ContentFreezeGuard {

            @ParameterizedTest
            @EnumSource(value = ArticleStatus.class, names = {"PENDING_REVIEW", "PUBLISHED", "ARCHIVED"})
            @DisplayName("凍結狀態還原 → A0209，且無任何寫入／事件／標籤／檔案綁定副作用")
            void applyRestoreContent_frozenStatus_throwsAndHasNoSideEffect(ArticleStatus frozen) {
                existing.setStatus(frozen);
                existing.setTitle("送審當下的內容 A");
                existing.setContent("A");
                ArticleRestoreSource data = new ArticleRestoreSource("偷換成的內容 B", "s", "B", "sum", null, List.of());

                assertThatThrownBy(() -> facade.applyRestoreContent(articleUuid, data))
                        .isInstanceOf(BusinessException.class)
                        .extracting(t -> ((BusinessException) t).getCode())
                        .isEqualTo("A0209");

                assertThat(existing.getTitle())
                        .as("守衛必須在 mutate 之前擋下，entity 不得被改到")
                        .isEqualTo("送審當下的內容 A");
                assertThat(existing.getContent()).isEqualTo("A");
                assertThat(existing.getStatus()).isEqualTo(frozen);
                verify(articleRepository, never()).save(any(Article.class));
                verifyNoInteractions(tagFacade, articleEventPublisher);
            }

            @ParameterizedTest
            @EnumSource(value = ArticleStatus.class, names = {"DRAFT", "REJECTED"})
            @DisplayName("DRAFT / REJECTED 還原 → 仍然成功（守住還原的正常用途，避免修過頭）")
            void applyRestoreContent_editableStatus_stillRestores(ArticleStatus editable) {
                existing.setStatus(editable);
                existing.setContent("舊內容");
                ArticleRestoreSource data = new ArticleRestoreSource("還原後標題", "s", "還原後內容", "sum", null, List.of());

                facade.applyRestoreContent(articleUuid, data);

                assertThat(existing.getContent()).isEqualTo("還原後內容");
                assertThat(existing.getTitle()).isEqualTo("還原後標題");
                assertThat(existing.getStatus())
                        .as("還原只還原內容，狀態一律不動（SEC-02）")
                        .isEqualTo(editable);
                verify(articleRepository).save(existing);
                verify(articleEventPublisher).publishContentChanged(existing,
                        ArticleContentChangedEvent.Action.RESTORED);
            }
        }
    }

    // ─── M6-a：推薦查詢的作者暱稱改由 UserFacade UUID 版批次解析（取代 INNER JOIN users）───

    /**
     * 推薦查詢原為 {@code INNER JOIN users}：查無作者的文章整列被排除。改為「文章列帶 authorUuid ＋
     * 整頁只呼叫一次 {@code UserFacade.getDisplayInfoByUuids}」後，必須維持同樣的語意。
     */
    @Nested
    @DisplayName("推薦查詢作者解析（維持 INNER JOIN 語意）")
    class RecommendAuthorResolution {

        private final UUID otherAuthor = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        private final UUID missingAuthor = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

        private ArticleSummaryRow rowBy(UUID articleUuid, UUID authorUuid, String title) {
            ArticleSummaryRow r = row(ARTICLE_ID, articleUuid, title);
            r.setAuthorUuid(authorUuid);
            return r;
        }

        private void stubAuthors() {
            when(userFacade.getDisplayInfoByUuids(any())).thenReturn(java.util.Map.of(
                    AUTHOR_UUID, new UserDisplayInfo(AUTHOR_UUID, "Author", null),
                    otherAuthor, new UserDisplayInfo(otherAuthor, "Other", "https://img/o.png")));
        }

        @Test
        @DisplayName("getRecentPublishedArticles：查無作者的文章被排除，其餘照常且暱稱取自 UserFacade")
        void getRecentPublishedArticles_excludesRowsWhoseAuthorIsMissing() {
            UUID kept = UUID.randomUUID();
            UUID dropped = UUID.randomUUID();
            stubAuthors();
            when(recommendMapper.findRecentPublished(ARTICLE_UUID, 5)).thenReturn(List.of(
                    rowBy(kept, otherAuthor, "Kept"), rowBy(dropped, missingAuthor, "Dropped")));
            when(recommendMapper.findTagsByArticleUuids(any())).thenReturn(List.of());

            List<ArticleSummaryInfo> result = facade.getRecentPublishedArticles(ARTICLE_UUID, 5);

            assertThat(result).extracting(ArticleSummaryInfo::title).containsExactly("Kept");
            assertThat(result.get(0).authorNickname()).isEqualTo("Other");
        }

        @Test
        @DisplayName("getArticlesByTagIds：查無作者的文章被排除，且只為留下的文章查標籤")
        void getArticlesByTagIds_excludesRowsWhoseAuthorIsMissing_andOnlyQueriesTagsForKept() {
            UUID tagUuid = UUID.randomUUID();
            UUID kept = UUID.randomUUID();
            UUID dropped = UUID.randomUUID();
            stubAuthors();
            when(recommendMapper.findByTagIds(List.of(tagUuid.toString()), ARTICLE_UUID, 5)).thenReturn(List.of(
                    rowBy(dropped, missingAuthor, "Dropped"), rowBy(kept, AUTHOR_UUID, "Kept")));
            when(recommendMapper.findTagsByArticleUuids(any())).thenReturn(List.of());

            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(List.of(tagUuid), ARTICLE_UUID, 5);

            assertThat(result).extracting(ArticleSummaryInfo::title).containsExactly("Kept");
            verify(recommendMapper).findTagsByArticleUuids(List.of(kept.toString()));
        }

        @Test
        @DisplayName("getPublishedArticlesByUuids：查無作者的文章被排除")
        void getPublishedArticlesByUuids_excludesRowsWhoseAuthorIsMissing() {
            UUID kept = UUID.randomUUID();
            UUID dropped = UUID.randomUUID();
            stubAuthors();
            when(recommendMapper.findByUuids(any())).thenReturn(List.of(
                    rowBy(kept, AUTHOR_UUID, "Kept"), rowBy(dropped, missingAuthor, "Dropped")));
            when(recommendMapper.findTagsByArticleUuids(any())).thenReturn(List.of());

            List<ArticleSummaryInfo> result = facade.getPublishedArticlesByUuids(List.of(kept, dropped));

            assertThat(result).extracting(ArticleSummaryInfo::title).containsExactly("Kept");
        }

        @Test
        @DisplayName("所有作者都查無 → 回傳空列表，且不再查標籤")
        void allAuthorsMissing_returnsEmptyAndSkipsTagQuery() {
            when(userFacade.getDisplayInfoByUuids(any())).thenReturn(java.util.Map.of());
            when(recommendMapper.findRecentPublished(ARTICLE_UUID, 5)).thenReturn(List.of(
                    rowBy(UUID.randomUUID(), missingAuthor, "A"), rowBy(UUID.randomUUID(), missingAuthor, "B")));

            List<ArticleSummaryInfo> result = facade.getRecentPublishedArticles(ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findTagsByArticleUuids(any());
        }

        @Test
        @DisplayName("整頁作者只呼叫 UserFacade 一次（不逐筆 N+1），同一作者去重")
        void resolvesAuthorsWithSingleFacadeCall_distinct() {
            stubAuthors();
            when(recommendMapper.findRecentPublished(ARTICLE_UUID, 5)).thenReturn(List.of(
                    rowBy(UUID.randomUUID(), AUTHOR_UUID, "A"),
                    rowBy(UUID.randomUUID(), AUTHOR_UUID, "B"),
                    rowBy(UUID.randomUUID(), otherAuthor, "C")));
            when(recommendMapper.findTagsByArticleUuids(any())).thenReturn(List.of());

            facade.getRecentPublishedArticles(ARTICLE_UUID, 5);

            org.mockito.ArgumentCaptor<java.util.Collection<UUID>> captor =
                    org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
            verify(userFacade, times(1)).getDisplayInfoByUuids(captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(AUTHOR_UUID, otherAuthor);
        }

        @Test
        @DisplayName("查詢結果為空 → 不呼叫 UserFacade")
        void emptyRows_doesNotCallUserFacade() {
            when(recommendMapper.findRecentPublished(ARTICLE_UUID, 5)).thenReturn(List.of());

            assertThat(facade.getRecentPublishedArticles(ARTICLE_UUID, 5)).isEmpty();

            verify(userFacade, never()).getDisplayInfoByUuids(any());
        }
    }
}
