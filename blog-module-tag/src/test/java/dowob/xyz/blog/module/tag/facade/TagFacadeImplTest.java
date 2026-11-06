package dowob.xyz.blog.module.tag.facade;

import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.service.TagNormalizationService;
import dowob.xyz.blog.module.tag.service.TagUsageRecalculator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dowob.xyz.blog.common.api.errorcode.TagErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagFacadeImpl 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TagFacadeImpl 單元測試")
class TagFacadeImplTest {

    @Mock
    private TagNormalizationService tagNormalizationService;

    @Mock
    private ArticleTagRepository articleTagRepository;

    @Mock
    private TagUsageRecalculator tagUsageRecalculator;

    @InjectMocks
    private TagFacadeImpl tagFacadeImpl;

    /**
     * 建立測試用 Tag，UUID、name、slug 均填入
     */
    private Tag buildTag(UUID id, String name, String slug) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setName(name);
        tag.setSlug(slug);
        return tag;
    }

    /** 讓 mock 的 normalize 行為近似真實實作（轉小寫），供排序鍵使用 */
    private void stubNormalizeAsLowerCase() {
        lenient().when(tagNormalizationService.normalize(anyString()))
                .thenAnswer(invocation -> invocation.<String>getArgument(0).trim().toLowerCase());
    }

    /** ----------------------------------------------------------------------- */
    /** findOrCreateTags                                                        */
    /** ----------------------------------------------------------------------- */

    @Nested
    @DisplayName("findOrCreateTags")
    class FindOrCreateTagsTests {

        @Test
        @DisplayName("正常：傳入兩個 tagName，各呼叫一次 findOrCreate，回傳兩個 TagInfo")
        void findOrCreateTags_callsFindOrCreateForEachName() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            Tag tag1 = buildTag(id1, "Spring", "spring");
            Tag tag2 = buildTag(id2, "Java", "java");

            stubNormalizeAsLowerCase();
            when(tagNormalizationService.findOrCreate("Spring")).thenReturn(tag1);
            when(tagNormalizationService.findOrCreate("Java")).thenReturn(tag2);

            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(List.of("Spring", "Java"));

            verify(tagNormalizationService).findOrCreate("Spring");
            verify(tagNormalizationService).findOrCreate("Java");
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("正常：回傳的 TagInfo 包含正確的 id、name、slug")
        void findOrCreateTags_returnsTagInfoWithCorrectFields() {
            UUID id1 = UUID.randomUUID();
            Tag tag1 = buildTag(id1, "Spring", "spring");
            stubNormalizeAsLowerCase();
            when(tagNormalizationService.findOrCreate("Spring")).thenReturn(tag1);

            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(List.of("Spring"));

            assertThat(result).hasSize(1);
            TagInfo info = result.get(0);
            assertThat(info.id()).isEqualTo(id1);
            assertThat(info.name()).isEqualTo("Spring");
            assertThat(info.slug()).isEqualTo("spring");
        }

        @Test
        @DisplayName("死結防護：依 slug 排序的固定順序呼叫 findOrCreate，回傳順序仍與輸入一致")
        void findOrCreateTags_createsInSlugOrder_butReturnsInInputOrder() {
            stubNormalizeAsLowerCase();
            Tag zeta = buildTag(UUID.randomUUID(), "zeta", "zeta");
            Tag alpha = buildTag(UUID.randomUUID(), "alpha", "alpha");
            Tag mid = buildTag(UUID.randomUUID(), "mid", "mid");
            when(tagNormalizationService.findOrCreate("Zeta")).thenReturn(zeta);
            when(tagNormalizationService.findOrCreate("Alpha")).thenReturn(alpha);
            when(tagNormalizationService.findOrCreate("Mid")).thenReturn(mid);

            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(List.of("Zeta", "Alpha", "Mid"));

            InOrder order = inOrder(tagNormalizationService);
            order.verify(tagNormalizationService).findOrCreate("Alpha");
            order.verify(tagNormalizationService).findOrCreate("Mid");
            order.verify(tagNormalizationService).findOrCreate("Zeta");
            assertThat(result).extracting(TagInfo::slug).containsExactly("zeta", "alpha", "mid");
        }

        @Test
        @DisplayName("死結防護：不同順序的輸入，呼叫 findOrCreate 的順序相同")
        void findOrCreateTags_sameCreationOrderForDifferentInputOrders() {
            stubNormalizeAsLowerCase();
            when(tagNormalizationService.findOrCreate(anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                return buildTag(UUID.randomUUID(), name, name);
            });

            tagFacadeImpl.findOrCreateTags(List.of("b", "c", "a"));
            tagFacadeImpl.findOrCreateTags(List.of("c", "a", "b"));

            InOrder order = inOrder(tagNormalizationService);
            for (int i = 0; i < 2; i++) {
                order.verify(tagNormalizationService).findOrCreate("a");
                order.verify(tagNormalizationService).findOrCreate("b");
                order.verify(tagNormalizationService).findOrCreate("c");
            }
        }

        @Test
        @DisplayName("死結防護：slug 相同（C++／C#）時再比正規化後的 name，順序固定；兩者都被建立且依輸入位置回傳")
        void findOrCreateTags_sameSlug_tieBrokenByNormalizedName() {
            stubNormalizeAsLowerCase();
            Tag first = buildTag(UUID.randomUUID(), "c#", "c");
            Tag second = buildTag(UUID.randomUUID(), "c++", "c");
            when(tagNormalizationService.findOrCreate("C++")).thenReturn(second);
            when(tagNormalizationService.findOrCreate("C#")).thenReturn(first);

            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(List.of("C++", "C#"));

            InOrder order = inOrder(tagNormalizationService);
            order.verify(tagNormalizationService).findOrCreate("C#");
            order.verify(tagNormalizationService).findOrCreate("C++");
            assertThat(result).extracting(TagInfo::id).containsExactly(second.getId(), first.getId());
        }

        @Test
        @DisplayName("PERF-27：已存在的標籤以單一批次查詢讀出，只對缺少的才 findOrCreate（且仍依 slug 固定順序）")
        void findOrCreateTags_batchReadsExisting_createsOnlyMissingInSlugOrder() {
            stubNormalizeAsLowerCase();
            Tag existingMid = buildTag(UUID.randomUUID(), "mid", "mid");
            Tag createdZeta = buildTag(UUID.randomUUID(), "zeta", "zeta");
            Tag createdAlpha = buildTag(UUID.randomUUID(), "alpha", "alpha");
            when(tagNormalizationService.findExistingBySlugs(org.mockito.ArgumentMatchers.anyCollection()))
                    .thenReturn(java.util.Map.of("mid", existingMid));
            when(tagNormalizationService.findOrCreate("Zeta")).thenReturn(createdZeta);
            when(tagNormalizationService.findOrCreate("Alpha")).thenReturn(createdAlpha);

            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(List.of("Zeta", "Mid", "Alpha"));

            verify(tagNormalizationService, times(1)).findExistingBySlugs(org.mockito.ArgumentMatchers.anyCollection());
            verify(tagNormalizationService, never()).findOrCreate("Mid");
            InOrder order = inOrder(tagNormalizationService);
            order.verify(tagNormalizationService).findOrCreate("Alpha");
            order.verify(tagNormalizationService).findOrCreate("Zeta");
            assertThat(result).extracting(TagInfo::slug).containsExactly("zeta", "mid", "alpha");
            assertThat(result.get(1).id()).isEqualTo(existingMid.getId());
        }

        @Test
        @DisplayName("PERF-27：全部標籤都已存在 → 一次批次查詢，完全不呼叫 findOrCreate")
        void findOrCreateTags_allExisting_noFindOrCreate() {
            stubNormalizeAsLowerCase();
            Tag a = buildTag(UUID.randomUUID(), "a", "a");
            Tag b = buildTag(UUID.randomUUID(), "b", "b");
            when(tagNormalizationService.findExistingBySlugs(org.mockito.ArgumentMatchers.anyCollection()))
                    .thenReturn(java.util.Map.of("a", a, "b", b));

            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(List.of("B", "A"));

            verify(tagNormalizationService, never()).findOrCreate(anyString());
            assertThat(result).extracting(TagInfo::slug).containsExactly("b", "a");
        }

        @Test
        @DisplayName("blank 名稱提早拋 BusinessException：在建立任何標籤之前就失敗")
        void findOrCreateTags_blankName_failsBeforeCreatingAnything() {
            when(tagNormalizationService.normalize("  ")).thenThrow(new BusinessException(TagErrorCode.TAG_INVALID_NAME));
            lenient().when(tagNormalizationService.normalize("Java")).thenReturn("java");

            assertThatThrownBy(() -> tagFacadeImpl.findOrCreateTags(List.of("Java", "  ")))
                    .isInstanceOf(BusinessException.class);

            verify(tagNormalizationService, never()).findOrCreate(anyString());
        }

        @Test
        @DisplayName("邊界：空列表傳入，回傳空列表，findOrCreate 不被呼叫")
        void findOrCreateTags_emptyInput_returnsEmptyList() {
            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(List.of());

            assertThat(result).isEmpty();
            verify(tagNormalizationService, never()).findOrCreate(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("防護：null 傳入，回傳空列表，不拋 NPE")
        void findOrCreateTags_nullInput_returnsEmptyList() {
            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(null);

            assertThat(result).isEmpty();
            verify(tagNormalizationService, never()).findOrCreate(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /** ----------------------------------------------------------------------- */
    /** syncArticleTags                                                         */
    /** ----------------------------------------------------------------------- */

    @Nested
    @DisplayName("syncArticleTags")
    class SyncArticleTagsTests {

        @Test
        @DisplayName("正常：只刪除被移除的舊標籤、只新增新加入的標籤（各一次批次呼叫）")
        void syncArticleTags_deletesOldAndSavesNew() {
            UUID articleUuid = UUID.randomUUID();
            UUID existingTagId = UUID.randomUUID();
            when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of(existingTagId));

            UUID newTagId = UUID.randomUUID();
            tagFacadeImpl.syncArticleTags(articleUuid, List.of(newTagId));

            verify(articleTagRepository).deleteByArticleIdAndTagIds(articleUuid, List.of(existingTagId));
            verify(articleTagRepository).saveAll(articleUuid, List.of(newTagId));
        }

        @Test
        @DisplayName("正常：多個舊標籤與多個新標籤，仍只各用一次批次刪除／批次新增")
        void syncArticleTags_deletesAllOldAndSavesAllNew() {
            UUID articleUuid = UUID.randomUUID();
            UUID oldId1 = new UUID(1L, 1L);
            UUID oldId2 = new UUID(1L, 2L);
            when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of(oldId1, oldId2));

            UUID newId1 = new UUID(2L, 1L);
            UUID newId2 = new UUID(2L, 2L);
            tagFacadeImpl.syncArticleTags(articleUuid, List.of(newId1, newId2));

            verify(articleTagRepository, times(1)).deleteByArticleIdAndTagIds(articleUuid, List.of(oldId1, oldId2));
            verify(articleTagRepository, times(1)).saveAll(articleUuid, List.of(newId1, newId2));
            verify(articleTagRepository, never()).deleteByArticleIdAndTagId(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            verify(articleTagRepository, never()).save(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("邊界：無舊標籤時，不刪除，仍新增新標籤")
        void syncArticleTags_noExistingTags_onlySaves() {
            UUID articleUuid = UUID.randomUUID();
            when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of());

            UUID newTagId = UUID.randomUUID();
            tagFacadeImpl.syncArticleTags(articleUuid, List.of(newTagId));

            verify(articleTagRepository, never()).deleteByArticleIdAndTagIds(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyCollection());
            verify(articleTagRepository).saveAll(articleUuid, List.of(newTagId));
        }

        @Test
        @DisplayName("防護：tagIds 為 null 時，只清除舊關聯，不拋 NPE")
        void syncArticleTags_nullTagIds_deletesOldAndDoesNotThrow() {
            UUID articleUuid = UUID.randomUUID();
            UUID existingTagId = UUID.randomUUID();
            when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of(existingTagId));

            tagFacadeImpl.syncArticleTags(articleUuid, null);

            verify(articleTagRepository).deleteByArticleIdAndTagIds(articleUuid, List.of(existingTagId));
            verify(articleTagRepository, never()).saveAll(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        }

        @Test
        @DisplayName("PERF-27：只改一個標籤（[A,B,C] → [A,B,D]）→ 只刪 C、只增 D，未變動的 A、B 完全不被碰")
        void syncArticleTags_changeOneTag_onlyTouchesTheDifference() {
            UUID articleUuid = UUID.randomUUID();
            UUID a = new UUID(0L, 1L);
            UUID b = new UUID(0L, 2L);
            UUID c = new UUID(0L, 3L);
            UUID d = new UUID(0L, 4L);
            when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of(a, b, c));

            tagFacadeImpl.syncArticleTags(articleUuid, List.of(a, b, d));

            verify(articleTagRepository).deleteByArticleIdAndTagIds(articleUuid, List.of(c));
            verify(articleTagRepository).saveAll(articleUuid, List.of(d));
            verify(articleTagRepository, never()).deleteByArticleId(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("PERF-27：新舊集合完全相同 → 不刪、不增（零寫入）；輸入重複與順序不同也視為相同")
        void syncArticleTags_sameSet_noWrites() {
            UUID articleUuid = UUID.randomUUID();
            UUID a = new UUID(0L, 1L);
            UUID b = new UUID(0L, 2L);
            when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of(a, b));

            tagFacadeImpl.syncArticleTags(articleUuid, List.of(b, a, b));

            verify(articleTagRepository, never()).deleteByArticleIdAndTagIds(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyCollection());
            verify(articleTagRepository, never()).saveAll(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        }
    }

    /** ----------------------------------------------------------------------- */
    /** deleteArticleTags                                                       */
    /** ----------------------------------------------------------------------- */

    @Nested
    @DisplayName("deleteArticleTags")
    class DeleteArticleTagsTests {

        @Test
        @DisplayName("正常：有兩個標籤，每個都呼叫 deleteByArticleIdAndTagId")
        void deleteArticleTags_deletesAll() {
            UUID articleUuid = UUID.randomUUID();
            UUID tagId1 = UUID.randomUUID();
            UUID tagId2 = UUID.randomUUID();
            Tag tag1 = buildTag(tagId1, "Tag1", "tag-1");
            Tag tag2 = buildTag(tagId2, "Tag2", "tag-2");
            when(articleTagRepository.findTagsByArticleId(articleUuid)).thenReturn(List.of(tag1, tag2));

            tagFacadeImpl.deleteArticleTags(articleUuid);

            verify(articleTagRepository).deleteByArticleIdAndTagId(articleUuid, tagId1);
            verify(articleTagRepository).deleteByArticleIdAndTagId(articleUuid, tagId2);
            verify(articleTagRepository, times(2)).deleteByArticleIdAndTagId(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("邊界：無標籤時，不呼叫 deleteByArticleIdAndTagId")
        void deleteArticleTags_noTags_doesNothing() {
            UUID articleUuid = UUID.randomUUID();
            when(articleTagRepository.findTagsByArticleId(articleUuid)).thenReturn(List.of());

            tagFacadeImpl.deleteArticleTags(articleUuid);

            verify(articleTagRepository, never()).deleteByArticleIdAndTagId(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    /** ----------------------------------------------------------------------- */
    /** findTagIdsByArticleUuid                                                 */
    /** ----------------------------------------------------------------------- */

    @Nested
    @DisplayName("findTagIdsByArticleUuid")
    class FindTagIdsByArticleUuidTests {

        @Test
        @DisplayName("正常：兩個標籤，回傳對應 UUID 列表")
        void findTagIdsByArticleUuid_returnsTagIds() {
            UUID articleUuid = UUID.randomUUID();
            UUID tagId1 = UUID.randomUUID();
            UUID tagId2 = UUID.randomUUID();
            when(articleTagRepository.findTagsByArticleId(articleUuid))
                    .thenReturn(List.of(buildTag(tagId1, "T1", "t-1"), buildTag(tagId2, "T2", "t-2")));

            List<UUID> result = tagFacadeImpl.findTagIdsByArticleUuid(articleUuid);

            assertThat(result).containsExactly(tagId1, tagId2);
        }

        @Test
        @DisplayName("邊界：無標籤時回傳空列表（非 null）")
        void findTagIdsByArticleUuid_noTags_returnsEmpty() {
            UUID articleUuid = UUID.randomUUID();
            when(articleTagRepository.findTagsByArticleId(articleUuid)).thenReturn(List.of());

            List<UUID> result = tagFacadeImpl.findTagIdsByArticleUuid(articleUuid);

            assertThat(result).isNotNull().isEmpty();
        }
    }

    /** ----------------------------------------------------------------------- */
    /** 插入順序與被移除標籤的重算                                              */
    /** ----------------------------------------------------------------------- */

    @Nested
    @DisplayName("syncArticleTags／deleteArticleTags：插入順序與被移除標籤重算")
    class RemovedTagsAndOrderingTests {

        /** 在「有外層交易同步」的情境下執行動作，回傳註冊的同步供測試手動觸發 commit／rollback */
        private List<TransactionSynchronization> withSynchronization(Runnable action) {
            TransactionSynchronizationManager.initSynchronization();
            try {
                action.run();
                return List.copyOf(TransactionSynchronizationManager.getSynchronizations());
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("新增與刪除都依 tag id 的 uuid 位元組無號順序（與資料庫 ORDER BY id 一致），而非輸入順序")
        void syncArticleTags_writesInUnsignedUuidOrder() {
            UUID articleUuid = UUID.randomUUID();
            UUID low = new UUID(1L, 0L);
            UUID high = new UUID(-1L, 0L);
            UUID oldLow = new UUID(2L, 0L);
            UUID oldHigh = new UUID(-2L, 0L);
            when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of(oldHigh, oldLow));

            tagFacadeImpl.syncArticleTags(articleUuid, List.of(high, low));

            verify(articleTagRepository).saveAll(articleUuid, List.of(low, high));
            verify(articleTagRepository).deleteByArticleIdAndTagIds(articleUuid, List.of(oldLow, oldHigh));
        }

        @Test
        @DisplayName("[A,B] → [A]：commit 之前不重算；commit 之後只對被移除的 B 重算")
        void syncArticleTags_removedTag_recalculatedOnlyAfterCommit() {
            UUID articleUuid = UUID.randomUUID();
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(articleTagRepository.findTagIdsByArticleId(articleUuid))
                    .thenReturn(List.of(a, b));

            List<TransactionSynchronization> syncs =
                    withSynchronization(() -> tagFacadeImpl.syncArticleTags(articleUuid, List.of(a)));

            verify(tagUsageRecalculator, never()).recalculateInNewTransaction(org.mockito.ArgumentMatchers.anyCollection());
            syncs.forEach(TransactionSynchronization::afterCommit);
            verify(tagUsageRecalculator).recalculateInNewTransaction(java.util.Set.of(b));
        }

        @Test
        @DisplayName("外層交易 rollback（只有 afterCompletion、沒有 afterCommit）：不重算")
        void syncArticleTags_rollback_doesNotRecalculate() {
            UUID articleUuid = UUID.randomUUID();
            UUID a = UUID.randomUUID();
            when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of(a));

            List<TransactionSynchronization> syncs =
                    withSynchronization(() -> tagFacadeImpl.syncArticleTags(articleUuid, List.of()));
            syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(tagUsageRecalculator, never()).recalculateInNewTransaction(org.mockito.ArgumentMatchers.anyCollection());
        }

        @Test
        @DisplayName("新舊集合相同或只有新增：沒有被移除的標籤，不註冊重算")
        void syncArticleTags_noRemovedTags_registersNothing() {
            UUID articleUuid = UUID.randomUUID();
            UUID a = UUID.randomUUID();
            UUID added = UUID.randomUUID();
            when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of(a));

            List<TransactionSynchronization> syncs =
                    withSynchronization(() -> tagFacadeImpl.syncArticleTags(articleUuid, List.of(a, added)));
            syncs.forEach(TransactionSynchronization::afterCommit);

            verify(tagUsageRecalculator, never()).recalculateInNewTransaction(org.mockito.ArgumentMatchers.anyCollection());
        }

        @Test
        @DisplayName("deleteArticleTags：commit 後對原本掛的全部標籤重算")
        void deleteArticleTags_recalculatesAllFormerTagsAfterCommit() {
            UUID articleUuid = UUID.randomUUID();
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(articleTagRepository.findTagsByArticleId(articleUuid))
                    .thenReturn(List.of(buildTag(a, "A", "a"), buildTag(b, "B", "b")));

            List<TransactionSynchronization> syncs =
                    withSynchronization(() -> tagFacadeImpl.deleteArticleTags(articleUuid));
            syncs.forEach(TransactionSynchronization::afterCommit);

            verify(tagUsageRecalculator).recalculateInNewTransaction(java.util.Set.of(a, b));
        }

        @Test
        @DisplayName("commit 後重算拋例外：吞掉只記 WARN（交易已 commit，由每日校正兜底）")
        void recalculationFailureAfterCommit_isSwallowed() {
            UUID articleUuid = UUID.randomUUID();
            UUID a = UUID.randomUUID();
            when(articleTagRepository.findTagsByArticleId(articleUuid)).thenReturn(List.of(buildTag(a, "A", "a")));
            when(tagUsageRecalculator.recalculateInNewTransaction(org.mockito.ArgumentMatchers.anyCollection()))
                    .thenThrow(new RuntimeException("DB down"));

            List<TransactionSynchronization> syncs =
                    withSynchronization(() -> tagFacadeImpl.deleteArticleTags(articleUuid));

            org.assertj.core.api.Assertions.assertThatCode(() -> syncs.forEach(TransactionSynchronization::afterCommit))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("沒有外層交易同步（auto-commit）：刪除已生效，立即重算")
        void withoutTransactionSynchronization_recalculatesImmediately() {
            UUID articleUuid = UUID.randomUUID();
            UUID a = UUID.randomUUID();
            when(articleTagRepository.findTagsByArticleId(articleUuid)).thenReturn(List.of(buildTag(a, "A", "a")));

            tagFacadeImpl.deleteArticleTags(articleUuid);

            verify(tagUsageRecalculator).recalculateInNewTransaction(java.util.Set.of(a));
        }
    }
}
