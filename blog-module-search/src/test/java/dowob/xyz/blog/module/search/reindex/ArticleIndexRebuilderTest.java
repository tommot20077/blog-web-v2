package dowob.xyz.blog.module.search.reindex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.index.ArticleIndexAdmin;
import dowob.xyz.blog.module.search.index.IndexState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleIndexRebuilder} 單元測試（D1／PERF-01）。
 *
 * <p>鎖定：分批讀取並 bulk 寫入新的實體索引、切換前後各補一次重建期間的變動、原子切換 alias 後刪舊索引；
 * 切換前任何一步失敗都<b>不切換</b> alias、刪除新索引；文件組裝沿用原 {@code reindexAll} 的規則
 * （作者暱稱取當下值、每批一次 UserFacade 查詢、只存 uuid／nickname、頂層 uuid）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleIndexRebuilder 單元測試")
class ArticleIndexRebuilderTest {

    private static final String OLD = "blog_articles_v20261101000000000";
    private static final String NEW = "blog_articles_v20261107120000000";
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    private ArticleIndexAdmin admin;
    private ReindexJournal journal;
    private ArticleFacade articleFacade;
    private UserFacade userFacade;
    private MutableClock clock;
    private ArticleIndexRebuilder rebuilder;
    private final List<Long> progress = new ArrayList<>();

    /** 可以撥動的時鐘（測逾時保護） */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration duration) {
            now.updateAndGet(i -> i.plus(duration));
        }
    }

    @BeforeEach
    void setUp() {
        admin = mock(ArticleIndexAdmin.class);
        journal = mock(ReindexJournal.class);
        articleFacade = mock(ArticleFacade.class);
        userFacade = mock(UserFacade.class);
        clock = new MutableClock();
        when(admin.inspect()).thenReturn(IndexState.alias(List.of(OLD)));
        when(admin.newVersionedIndexName()).thenReturn(NEW);
        when(admin.countLive()).thenReturn(0L);
        when(journal.drainArticles(anyInt())).thenReturn(List.of());
        when(journal.drainAuthors(anyInt())).thenReturn(List.of());
        when(articleFacade.findPublishedForIndexAfter(any(), anyInt())).thenReturn(List.of());
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(Map.of());
        rebuilder = new ArticleIndexRebuilder(admin, journal, articleFacade, userFacade, clock);
    }

    private long rebuild() {
        return rebuilder.rebuild(progress::add);
    }

    private static ArticleIndexData data(UUID uuid, UUID authorUuid, String snapshotNickname,
                                         List<ArticleIndexData.TagData> tags) {
        return new ArticleIndexData(uuid, "title-" + uuid, "slug-" + uuid, "summary", "純文字內文", snapshotNickname,
                LocalDateTime.of(2026, 11, 1, 9, 0), 0L, 0L, tags, authorUuid);
    }

    private static List<ArticleIndexData> batch(int size) {
        return IntStream.range(0, size).mapToObj(i -> data(UUID.randomUUID(), null, "Yuan", List.of())).toList();
    }

    @SuppressWarnings("unchecked")
    private List<ArticleDocument> capturedDocuments() {
        ArgumentCaptor<List<ArticleDocument>> captor = ArgumentCaptor.forClass(List.class);
        verify(admin, org.mockito.Mockito.atLeastOnce()).bulkIndex(captor.capture(), eq(NEW));
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    @Nested
    @DisplayName("成功路徑")
    class HappyPath {

        @Test
        @DisplayName("建新實體索引 → 開始記錄變動 → 分批寫入 → refresh → 切換前補齊 → 原子切換 alias → 切換後補齊 → 結束記錄 → 刪舊索引")
        void rebuild_followsAliasSwapSequence() {
            when(articleFacade.findPublishedForIndexAfter(null, ArticleIndexRebuilder.BATCH_SIZE)).thenReturn(batch(2));

            long written = rebuild();

            assertThat(written).isEqualTo(2);
            InOrder order = inOrder(admin, journal, articleFacade);
            order.verify(admin).createIndex(NEW);
            order.verify(journal).begin(NEW);
            order.verify(articleFacade).findPublishedForIndexAfter(null, ArticleIndexRebuilder.BATCH_SIZE);
            order.verify(admin).bulkIndex(anyList(), eq(NEW));
            order.verify(admin).refresh(NEW);
            order.verify(journal).drainArticles(anyInt());
            order.verify(admin).switchAlias(NEW, IndexState.alias(List.of(OLD)));
            order.verify(journal).drainArticles(anyInt());
            order.verify(journal).end();
            order.verify(admin).deleteIndexQuietly(OLD);
        }

        @Test
        @DisplayName("450 篇、每批 200：keyset 游標依序為 null／第一批末筆／第二批末筆，讀取次數＝批數 3，每批一次 UserFacade 查詢")
        void rebuild_readsInKeysetBatches() {
            List<ArticleIndexData> first = batch(200);
            List<ArticleIndexData> second = batch(200);
            List<ArticleIndexData> third = batch(50);
            UUID cursor1 = first.get(199).articleUuid();
            UUID cursor2 = second.get(199).articleUuid();
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenReturn(first);
            when(articleFacade.findPublishedForIndexAfter(cursor1, 200)).thenReturn(second);
            when(articleFacade.findPublishedForIndexAfter(cursor2, 200)).thenReturn(third);

            long written = rebuild();

            assertThat(written).isEqualTo(450);
            verify(articleFacade, times(3)).findPublishedForIndexAfter(any(), eq(200));
            verify(admin, times(3)).bulkIndex(anyList(), eq(NEW));
            verify(journal, times(3)).keepAlive();
            assertThat(progress).as("每批之後回報一次，切換前再回報一次").containsExactly(200L, 400L, 450L, 450L);
            assertThat(capturedDocuments()).hasSize(450);
        }

        @Test
        @DisplayName("剛好整除（200 篇）：讀到一批空的才停，不會漏讀也不會無限迴圈")
        void rebuild_exactMultipleOfBatchSize_stopsOnEmptyBatch() {
            List<ArticleIndexData> first = batch(200);
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenReturn(first);

            assertThat(rebuild()).isEqualTo(200);
            verify(articleFacade).findPublishedForIndexAfter(first.get(199).articleUuid(), 200);
        }

        @Test
        @DisplayName("名稱原本是實體索引：切換時同一請求刪除它（remove_index），不另外刪除 blog_articles")
        void rebuild_fromConcreteIndex_migratesAtomically() {
            when(admin.inspect()).thenReturn(IndexState.concrete());
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenReturn(batch(1));

            rebuild();

            verify(admin).switchAlias(NEW, IndexState.concrete());
            verify(admin, never()).deleteIndexQuietly(ArticleIndexAdmin.ALIAS);
        }

        @Test
        @DisplayName("名稱原本不存在：直接建立 alias；沒有舊索引可刪")
        void rebuild_fromAbsent_createsAlias() {
            when(admin.inspect()).thenReturn(IndexState.absent());

            rebuild();

            verify(admin).switchAlias(NEW, IndexState.absent());
            verify(admin, never()).countLive();
            verify(admin, never()).deleteIndexQuietly(anyString());
        }

        @Test
        @DisplayName("資料庫與現行索引都沒有文章：照常切換（空索引不是異常）")
        void rebuild_emptyEverywhere_switches() {
            when(admin.countLive()).thenReturn(0L);

            assertThat(rebuild()).isZero();
            verify(admin).switchAlias(NEW, IndexState.alias(List.of(OLD)));
        }
    }

    @Nested
    @DisplayName("重建期間的增量變動（切換前後各補一次）")
    class CatchUp {

        @Test
        @DisplayName("切換前：仍為 PUBLISHED 的寫入新索引、已下架／已刪除的從新索引移除；切換後再補一次，最後才結束記錄")
        void rebuild_drainsJournalBeforeAndAfterSwitch() {
            UUID republished = UUID.randomUUID();
            UUID archived = UUID.randomUUID();
            UUID lateUpdate = UUID.randomUUID();
            when(journal.drainArticles(anyInt())).thenReturn(
                    List.of(republished.toString(), archived.toString()), List.of(),
                    List.of(lateUpdate.toString()), List.of());
            when(articleFacade.findPublishedForIndexByUuids(List.of(republished, archived)))
                    .thenReturn(List.of(data(republished, null, "Yuan", List.of())));
            when(articleFacade.findPublishedForIndexByUuids(List.of(lateUpdate)))
                    .thenReturn(List.of(data(lateUpdate, null, "Yuan", List.of())));

            rebuild();

            InOrder order = inOrder(admin, journal);
            order.verify(admin).bulkIndex(org.mockito.ArgumentMatchers.argThat(docs ->
                    docs.size() == 1 && docs.get(0).getId().equals(republished.toString())), eq(NEW));
            order.verify(admin).deleteDocument(archived.toString(), NEW);
            order.verify(admin).switchAlias(NEW, IndexState.alias(List.of(OLD)));
            order.verify(admin).bulkIndex(org.mockito.ArgumentMatchers.argThat(docs ->
                    docs.size() == 1 && docs.get(0).getId().equals(lateUpdate.toString())), eq(NEW));
            order.verify(journal).end();
            verify(admin, never()).deleteDocument(republished.toString(), NEW);
        }

        @Test
        @DisplayName("重建期間被刪帳匿名化的作者：在新索引上以當下暱稱改寫（查無此人用通用匿名暱稱），PII 不會隨切換回到索引")
        void rebuild_rewritesDirtyAuthorsOnNewIndex() {
            UUID anonymized = UUID.randomUUID();
            UUID vanished = UUID.randomUUID();
            when(journal.drainAuthors(anyInt())).thenReturn(List.of(anonymized.toString(), vanished.toString()), List.of());
            when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(Map.of(
                    anonymized, new UserDisplayInfo(anonymized, "已刪除使用者#ab12", null)));

            rebuild();

            verify(admin).updateAuthorNickname(anonymized, "已刪除使用者#ab12", NEW);
            verify(admin).updateAuthorNickname(vanished, ArticleDocument.ANONYMOUS_NICKNAME, NEW);
        }

        @Test
        @DisplayName("紀錄中混入非 UUID 的值：直接從新索引刪除該 id，不送進 facade")
        void rebuild_garbageJournalEntry_deletedNotQueried() {
            when(journal.drainArticles(anyInt())).thenReturn(List.of("not-a-uuid"), List.of());

            rebuild();

            verify(admin).deleteDocument("not-a-uuid", NEW);
            verify(articleFacade, never()).findPublishedForIndexByUuids(any());
        }

        @Test
        @DisplayName("切換後的補齊失敗：alias 已在新索引上，不刪新索引；錯誤說明已切換並建議重跑；舊索引照樣刪除、記錄照樣結束")
        void rebuild_postSwitchCatchUpFails_reportsSwitched() {
            when(journal.drainArticles(anyInt())).thenReturn(List.of()).thenThrow(new IllegalStateException("redis down"));

            assertThatThrownBy(() -> rebuild())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已切換").hasMessageContaining(NEW);
            verify(admin).switchAlias(NEW, IndexState.alias(List.of(OLD)));
            verify(admin, never()).deleteIndexQuietly(NEW);
            verify(admin).deleteIndexQuietly(OLD);
            verify(journal).end();
        }
    }

    @Nested
    @DisplayName("失敗時不切換 alias")
    class FailureKeepsOldIndex {

        private void assertAliasUntouchedAndNewIndexDeleted() {
            verify(admin, never()).switchAlias(anyString(), any());
            verify(admin).deleteIndexQuietly(NEW);
            verify(admin, never()).deleteIndexQuietly(OLD);
            verify(journal).end();
        }

        @Test
        @DisplayName("bulk 寫入失敗：不切換、刪除新索引（alias 不會指到半成品）")
        void rebuild_bulkFails() {
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenReturn(batch(3));
            doThrow(new IllegalStateException("bulk failed")).when(admin).bulkIndex(anyList(), eq(NEW));

            assertThatThrownBy(() -> rebuild()).hasMessage("bulk failed");
            assertAliasUntouchedAndNewIndexDeleted();
        }

        @Test
        @DisplayName("讀到一半 DB 失敗：不切換、刪除新索引")
        void rebuild_facadeFailsMidway() {
            List<ArticleIndexData> first = batch(200);
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenReturn(first);
            when(articleFacade.findPublishedForIndexAfter(first.get(199).articleUuid(), 200))
                    .thenThrow(new IllegalStateException("db down"));

            assertThatThrownBy(() -> rebuild()).hasMessage("db down");
            assertAliasUntouchedAndNewIndexDeleted();
        }

        @Test
        @DisplayName("進度回報拋例外（分散式鎖已遺失）：不切換——避免兩個重建同時切 alias")
        void rebuild_progressRejects() {
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenReturn(batch(1));

            assertThatThrownBy(() -> rebuilder.rebuild(p -> {
                throw new IllegalStateException("lock lost");
            })).hasMessage("lock lost");
            assertAliasUntouchedAndNewIndexDeleted();
        }

        @Test
        @DisplayName("切換前的補齊失敗：不切換、刪除新索引")
        void rebuild_preSwitchCatchUpFails() {
            when(journal.drainArticles(anyInt())).thenReturn(List.of(UUID.randomUUID().toString()));
            when(articleFacade.findPublishedForIndexByUuids(any())).thenThrow(new IllegalStateException("db down"));

            assertThatThrownBy(() -> rebuild()).hasMessage("db down");
            assertAliasUntouchedAndNewIndexDeleted();
        }

        @Test
        @DisplayName("alias 切換本身失敗（例如舊索引被別人刪了）：刪除新索引，alias 維持原狀（ES 對整個請求不生效）")
        void rebuild_switchFails() {
            doThrow(new IllegalStateException("aliases_not_found")).when(admin).switchAlias(eq(NEW), any());

            assertThatThrownBy(() -> rebuild()).hasMessage("aliases_not_found");
            verify(admin).deleteIndexQuietly(NEW);
            verify(admin, never()).deleteIndexQuietly(OLD);
        }

        @Test
        @DisplayName("資料庫回報 0 篇但現行索引仍有文件：視為讀取異常，不切換（寧可留舊索引，也不要把搜尋清空）")
        void rebuild_emptySnapshotButLiveIndexHasDocuments() {
            when(admin.countLive()).thenReturn(5L);

            assertThatThrownBy(() -> rebuild()).isInstanceOf(IllegalStateException.class).hasMessageContaining("0");
            assertAliasUntouchedAndNewIndexDeleted();
        }

        @Test
        @DisplayName("執行緒被中斷（應用程式關閉）：在下一批之前中止，不切換")
        void rebuild_interrupted() {
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenAnswer(inv -> {
                Thread.currentThread().interrupt();
                return batch(200);
            });
            try {
                assertThatThrownBy(() -> rebuild()).isInstanceOf(IllegalStateException.class).hasMessageContaining("中止");
            } finally {
                Thread.interrupted();
            }
            assertAliasUntouchedAndNewIndexDeleted();
        }

        @Test
        @DisplayName("超過最長執行時間（逾時保護）：中止，不切換")
        void rebuild_deadlineExceeded() {
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenAnswer(inv -> {
                clock.advance(ArticleIndexRebuilder.MAX_DURATION.plusMinutes(1));
                return batch(200);
            });

            assertThatThrownBy(() -> rebuild()).isInstanceOf(IllegalStateException.class).hasMessageContaining("逾時");
            assertAliasUntouchedAndNewIndexDeleted();
        }

        @Test
        @DisplayName("建立新索引失敗：沒有開始記錄變動、也沒有東西可清")
        void rebuild_createIndexFails() {
            doThrow(new IllegalStateException("es down")).when(admin).createIndex(NEW);

            assertThatThrownBy(() -> rebuild()).hasMessage("es down");
            verify(journal, never()).begin(anyString());
            verify(admin, never()).switchAlias(anyString(), any());
        }
    }

    @Nested
    @DisplayName("文件組裝（沿用原 reindexAll 的規則）")
    class DocumentAssembly {

        @Test
        @DisplayName("M3：作者暱稱以每批一次 UserFacade 查詢取當下值，不用快照內的舊暱稱；查無此人用通用匿名暱稱")
        void documents_useCurrentNicknames() {
            UUID authorA = UUID.randomUUID();
            UUID authorB = UUID.randomUUID();
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenReturn(List.of(
                    data(UUID.randomUUID(), authorA, "舊暱稱A", List.of()),
                    data(UUID.randomUUID(), authorA, "舊暱稱A", List.of()),
                    data(UUID.randomUUID(), authorB, "舊暱稱B", List.of())));
            when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(Map.of(
                    authorA, new UserDisplayInfo(authorA, "已刪除使用者#aaaa", null)));

            rebuild();

            verify(userFacade, times(1)).getDisplayInfoByUuids(anyCollection());
            assertThat(capturedDocuments()).extracting(d -> d.getAuthor().getNickname())
                    .containsExactly("已刪除使用者#aaaa", "已刪除使用者#aaaa", ArticleDocument.ANONYMOUS_NICKNAME);
        }

        @Test
        @DisplayName("文件 id 與頂層 uuid 皆為文章 UUID、status 為 PUBLISHED、內文為去 Markdown 的純文字、標籤正確轉換")
        void documents_carryIdentityStatusContentAndTags() {
            UUID uuid = UUID.randomUUID();
            UUID tagId = UUID.randomUUID();
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenReturn(List.of(
                    data(uuid, null, "Yuan", List.of(new ArticleIndexData.TagData(tagId, "Java", "java")))));

            rebuild();

            ArticleDocument doc = capturedDocuments().get(0);
            assertThat(doc.getId()).isEqualTo(uuid.toString());
            assertThat(doc.getUuid()).isEqualTo(uuid.toString());
            assertThat(doc.getStatus()).isEqualTo("PUBLISHED");
            assertThat(doc.getContent()).isEqualTo("純文字內文");
            assertThat(doc.getTitle()).isEqualTo("title-" + uuid);
            assertThat(doc.getSlug()).isEqualTo("slug-" + uuid);
            assertThat(doc.getPublishedAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 9, 0));
            assertThat(doc.getTags()).singleElement().satisfies(t -> {
                assertThat(t.getId()).isEqualTo(tagId);
                assertThat(t.getName()).isEqualTo("Java");
                assertThat(t.getSlug()).isEqualTo("java");
            });
        }

        @Test
        @DisplayName("tags 為 null 時轉成空清單")
        void documents_nullTags_becomeEmpty() {
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenReturn(List.of(
                    data(UUID.randomUUID(), null, "Yuan", null)));

            rebuild();

            assertThat(capturedDocuments().get(0).getTags()).isEmpty();
        }

        @Test
        @DisplayName("author 只由 uuid 與暱稱組成（不含 Long id 與 username）")
        void documents_authorHasOnlyUuidAndNickname() {
            UUID authorUuid = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenReturn(List.of(
                    data(UUID.randomUUID(), authorUuid, "暱稱", List.of())));
            when(userFacade.getDisplayInfoByUuids(anyCollection()))
                    .thenReturn(Map.of(authorUuid, new UserDisplayInfo(authorUuid, "暱稱", null)));

            rebuild();

            ArticleDocument doc = capturedDocuments().get(0);
            assertThat(doc.getAuthor().getUuid()).isEqualTo(authorUuid.toString());
            JsonNode authorJson = new ObjectMapper().valueToTree(doc.getAuthor());
            assertThat(authorJson.fieldNames()).toIterable().containsExactlyInAnyOrder("uuid", "nickname");
        }

        @Test
        @DisplayName("authorUuid 為 null（資料異常）：author.uuid 為 null、暱稱沿用快照，且不查 UserFacade")
        void documents_nullAuthorUuid_keepsSnapshotNickname() {
            when(articleFacade.findPublishedForIndexAfter(null, 200)).thenReturn(List.of(
                    data(UUID.randomUUID(), null, "快照暱稱", List.of())));

            rebuild();

            ArticleDocument doc = capturedDocuments().get(0);
            assertThat(doc.getAuthor().getUuid()).isNull();
            assertThat(doc.getAuthor().getNickname()).isEqualTo("快照暱稱");
            verify(userFacade, never()).getDisplayInfoByUuids(anyCollection());
        }
    }
}
