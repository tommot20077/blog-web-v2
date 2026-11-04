package dowob.xyz.blog.module.tag.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TagUsageRecalculator} 單元測試（DATA-01／RACE-07）：
 * 標籤使用計數 = 該標籤下「已發布」文章數，以重算取代遞增；Redis 熱門分數以 ZADD 設定。
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TagUsageRecalculator 單元測試")
class TagUsageRecalculatorTest {

    @Mock private TagRepository tagRepository;
    @Mock private ArticleTagRepository articleTagRepository;
    @Mock private ArticleFacade articleFacade;
    @Mock private RedisTemplate<String, String> stringRedisTemplate;
    @Mock private ZSetOperations<String, String> zSetOps;

    private TagUsageRecalculator recalculator;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        recalculator = new TagUsageRecalculator(tagRepository, articleTagRepository, articleFacade, stringRedisTemplate);
    }

    private static Tag tag(UUID id, String slug, int usageCount) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setSlug(slug);
        tag.setUsageCount(usageCount);
        return tag;
    }

    /** 讓 facade 只認 {@code published} 內的 UUID 為「已發布」（回傳輸入與其的交集） */
    private void givenPublished(Set<UUID> published) {
        when(articleFacade.filterPublishedUuids(anyCollection())).thenAnswer(inv -> {
            Set<UUID> result = new HashSet<>(inv.<Collection<UUID>>getArgument(0));
            result.retainAll(published);
            return result;
        });
    }

    @Test
    @DisplayName("標籤掛 3 篇文章、其中 2 篇已發布：usage_count 寫入 2，Redis 以 ZADD 設為 2.0，並清除標籤詳情快取")
    void recalculate_onlyPublishedArticlesAreCounted() {
        UUID tagId = UUID.randomUUID();
        UUID published1 = UUID.randomUUID();
        UUID published2 = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of(tag(tagId, "java", 0)));
        when(articleTagRepository.findArticleIdsByTagId(tagId)).thenReturn(List.of(published1, published2, draft));
        givenPublished(Set.of(published1, published2));

        int corrected = recalculator.recalculate(List.of(tagId));

        assertThat(corrected).isEqualTo(1);
        verify(tagRepository).updateUsageCount(tagId, 2);
        verify(zSetOps).add(RedisKeyConstant.TAG_HOT_KEY, tagId.toString(), 2.0);
        verify(stringRedisTemplate).delete(RedisKeyConstant.getTagDetailKey("java"));
    }

    @Test
    @DisplayName("標籤已無任何文章關聯：被灌水的舊計數歸零，且不需詢問 article facade")
    void recalculate_noArticleLinks_resetsInflatedCountToZero() {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of(tag(tagId, "java", 37)));
        when(articleTagRepository.findArticleIdsByTagId(tagId)).thenReturn(List.of());

        recalculator.recalculate(List.of(tagId));

        verify(tagRepository).updateUsageCount(tagId, 0);
        verify(zSetOps).add(RedisKeyConstant.TAG_HOT_KEY, tagId.toString(), 0.0);
        verifyNoInteractions(articleFacade);
    }

    @Test
    @DisplayName("標籤下的文章全是草稿（無已發布）：計數為 0")
    void recalculate_onlyDrafts_countIsZero() {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of(tag(tagId, "java", 2)));
        when(articleTagRepository.findArticleIdsByTagId(tagId)).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));
        givenPublished(Set.of());

        int corrected = recalculator.recalculate(List.of(tagId));

        assertThat(corrected).isEqualTo(1);
        verify(tagRepository).updateUsageCount(tagId, 0);
    }

    @Test
    @DisplayName("計數本來就正確：不發 UPDATE、不清詳情快取，但仍以 ZADD 對齊 Redis 分數")
    void recalculate_countUnchanged_skipsUpdateButStillSetsRedisScore() {
        UUID tagId = UUID.randomUUID();
        UUID published = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of(tag(tagId, "java", 1)));
        when(articleTagRepository.findArticleIdsByTagId(tagId)).thenReturn(List.of(published));
        givenPublished(Set.of(published));

        int corrected = recalculator.recalculate(List.of(tagId));

        assertThat(corrected).isZero();
        verify(tagRepository, never()).updateUsageCount(any(), anyInt());
        verify(stringRedisTemplate, never()).delete(RedisKeyConstant.getTagDetailKey("java"));
        verify(zSetOps).add(RedisKeyConstant.TAG_HOT_KEY, tagId.toString(), 1.0);
    }

    @Test
    @DisplayName("同一狀態重算兩次：結果不變（分數不累加，ZADD 兩次都是同一個絕對值）")
    void recalculate_replayWithSameState_isIdempotent() {
        UUID tagId = UUID.randomUUID();
        UUID published = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of(tag(tagId, "java", 1)));
        when(articleTagRepository.findArticleIdsByTagId(tagId)).thenReturn(List.of(published, UUID.randomUUID()));
        givenPublished(Set.of(published));

        recalculator.recalculate(List.of(tagId));
        recalculator.recalculate(List.of(tagId));

        verify(zSetOps, times(2)).add(RedisKeyConstant.TAG_HOT_KEY, tagId.toString(), 1.0);
        verify(tagRepository, never()).updateUsageCount(any(), anyInt());
    }

    @Test
    @DisplayName("不存在的 tagId（findAllByIdInForUpdate 查無）：靜默略過，不寫 DB、不動 Redis")
    void recalculate_unknownTagId_isSkipped() {
        UUID unknown = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of());

        int corrected = recalculator.recalculate(List.of(unknown));

        assertThat(corrected).isZero();
        verify(tagRepository, never()).updateUsageCount(any(), anyInt());
        verify(zSetOps, never()).add(any(), any(), anyDouble());
    }

    @Test
    @DisplayName("null 或空集合：什麼都不做（不碰任何依賴）")
    void recalculate_nullOrEmpty_doesNothing() {
        assertThat(recalculator.recalculate(null)).isZero();
        assertThat(recalculator.recalculate(List.of())).isZero();

        verifyNoInteractions(tagRepository, articleTagRepository, articleFacade, stringRedisTemplate);
    }

    @Test
    @DisplayName("tagIds 含重複與 null：去重並略過 null 後才鎖定查詢")
    void recalculate_duplicateAndNullIds_areDeduplicated() {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of());

        recalculator.recalculate(java.util.Arrays.asList(tagId, tagId, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(tagRepository).findAllByIdInForUpdate(captor.capture());
        assertThat(captor.getValue()).containsExactly(tagId);
    }

    @Test
    @DisplayName("回傳值是『計數被修正的標籤數』：兩個標籤一個正確一個錯誤 → 1")
    void recalculate_returnsNumberOfCorrectedTags() {
        UUID right = UUID.randomUUID();
        UUID wrong = UUID.randomUUID();
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection()))
                .thenReturn(List.of(tag(right, "right", 1), tag(wrong, "wrong", 9)));
        when(articleTagRepository.findArticleIdsByTagId(right)).thenReturn(List.of(a1));
        when(articleTagRepository.findArticleIdsByTagId(wrong)).thenReturn(List.of(a2));
        givenPublished(Set.of(a1, a2));

        int corrected = recalculator.recalculate(List.of(right, wrong));

        assertThat(corrected).isEqualTo(1);
        verify(tagRepository).updateUsageCount(wrong, 1);
        verify(tagRepository, never()).updateUsageCount(eq(right), anyInt());
    }

    @Test
    @DisplayName("全部 DB 寫入完成後才更新 Redis（Redis 無法 rollback）")
    void recalculate_updatesRedisOnlyAfterAllDbWrites() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID article = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection()))
                .thenReturn(List.of(tag(first, "a", 0), tag(second, "b", 0)));
        when(articleTagRepository.findArticleIdsByTagId(any())).thenReturn(List.of(article));
        givenPublished(Set.of(article));

        recalculator.recalculate(List.of(first, second));

        InOrder order = inOrder(tagRepository, zSetOps);
        order.verify(tagRepository).updateUsageCount(first, 1);
        order.verify(tagRepository).updateUsageCount(second, 1);
        order.verify(zSetOps).add(RedisKeyConstant.TAG_HOT_KEY, first.toString(), 1.0);
        order.verify(zSetOps).add(RedisKeyConstant.TAG_HOT_KEY, second.toString(), 1.0);
    }

    @Test
    @DisplayName("第 2 個標籤 DB 寫入失敗：例外往外拋，Redis 完全不被碰")
    void recalculate_secondDbWriteFails_neverTouchesRedis() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID article = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection()))
                .thenReturn(List.of(tag(first, "a", 0), tag(second, "b", 0)));
        when(articleTagRepository.findArticleIdsByTagId(any())).thenReturn(List.of(article));
        givenPublished(Set.of(article));
        when(tagRepository.updateUsageCount(eq(second), anyInt())).thenThrow(new RuntimeException("DB write error"));

        assertThatThrownBy(() -> recalculator.recalculate(List.of(first, second)))
                .isInstanceOf(RuntimeException.class);

        verify(zSetOps, never()).add(any(), any(), anyDouble());
    }

    @Test
    @DisplayName("article facade 查詢失敗：例外往外拋，不寫入任何計數")
    void recalculate_facadeFailure_propagatesWithoutWriting() {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of(tag(tagId, "java", 0)));
        when(articleTagRepository.findArticleIdsByTagId(tagId)).thenReturn(List.of(UUID.randomUUID()));
        when(articleFacade.filterPublishedUuids(anyCollection())).thenThrow(new RuntimeException("facade down"));

        assertThatThrownBy(() -> recalculator.recalculate(List.of(tagId))).isInstanceOf(RuntimeException.class);

        verify(tagRepository, never()).updateUsageCount(any(), anyInt());
        verify(zSetOps, never()).add(any(), any(), anyDouble());
    }

    @Test
    @DisplayName("recalculateForArticle：取該文章目前掛的標籤，重算這些標籤")
    void recalculateForArticle_recalculatesTagsOfThatArticle() {
        UUID articleUuid = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of(tagId));
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of(tag(tagId, "java", 5)));
        when(articleTagRepository.findArticleIdsByTagId(tagId)).thenReturn(List.of(articleUuid));
        givenPublished(Set.of());

        int corrected = recalculator.recalculateForArticle(articleUuid);

        assertThat(corrected).isEqualTo(1);
        verify(tagRepository).updateUsageCount(tagId, 0);
    }

    @Test
    @DisplayName("recalculateForArticle：文章沒有任何標籤 → 不重算")
    void recalculateForArticle_articleWithoutTags_doesNothing() {
        UUID articleUuid = UUID.randomUUID();
        when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of());

        assertThat(recalculator.recalculateForArticle(articleUuid)).isZero();

        verifyNoInteractions(tagRepository, articleFacade);
    }

    @Test
    @DisplayName("recalculateForArticle：articleUuid 為 null → 不查詢也不重算")
    void recalculateForArticle_nullArticleUuid_doesNothing() {
        assertThat(recalculator.recalculateForArticle(null)).isZero();

        verifyNoInteractions(tagRepository, articleTagRepository, articleFacade);
    }
}
