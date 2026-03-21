package dowob.xyz.blog.module.tag.service;

import dowob.xyz.blog.common.api.errorcode.TagErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.model.dto.TagDetailResponse;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import dowob.xyz.blog.module.tag.repository.UserTagFollowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagService unit tests
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TagService unit tests")
class TagServiceTest {

    @Mock
    private TagRepository tagRepository;

    @Mock
    private UserTagFollowRepository userTagFollowRepository;

    @Mock
    private RedisTemplate<String, String> stringRedisTemplate;

    @Mock
    private ArticleTagRepository articleTagRepository;

    @SuppressWarnings("rawtypes")
    @Mock
    private ZSetOperations zSetOps;

    @SuppressWarnings("rawtypes")
    @Mock
    private HashOperations hashOps;

    private TagServiceImpl tagService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        tagService = new TagServiceImpl(tagRepository, userTagFollowRepository, stringRedisTemplate, articleTagRepository);
    }

    @Test
    @DisplayName("TagErrorCode 格式符合 A03xx 規範")
    void tagErrorCode_code_isA03xx() {
        assertThat(TagErrorCode.TAG_NOT_FOUND.getCode()).startsWith("A03");
        assertThat(TagErrorCode.TAG_IN_USE.getCode()).startsWith("A03");
        assertThat(TagErrorCode.TAG_NAME_CONFLICT.getCode()).startsWith("A03");
        assertThat(TagErrorCode.TAG_SLUG_CONFLICT.getCode()).startsWith("A03");
        assertThat(TagErrorCode.TAG_INVALID_NAME.getCode()).startsWith("A03");
    }

    @Test
    @DisplayName("suggest: prefix queries Redis ZSet")
    @SuppressWarnings("unchecked")
    void suggest_withPrefix_queriesRedisZSet() {
        when(zSetOps.rangeByLex(anyString(), any(Range.class), any(Limit.class)))
                .thenReturn(Set.of("java", "javascript"));

        List<String> result = tagService.suggest("ja", 10);

        assertThat(result).containsExactlyInAnyOrder("java", "javascript");
        verify(zSetOps).rangeByLex(eq("tag:autocomplete"), any(Range.class), any(Limit.class));
    }

    @Test
    @DisplayName("getHotTags: limit <= 0 時回傳空列表")
    void getHotTags_limitZero_returnsEmpty() {
        List<Tag> result = tagService.getHotTags(0);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getHotTags: limit 負數時回傳空列表")
    void getHotTags_limitNegative_returnsEmpty() {
        List<Tag> result = tagService.getHotTags(-1);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getHotTags: cache hit returns from Redis")
    @SuppressWarnings("unchecked")
    void getHotTags_cacheHit_returnsFromRedis() {
        UUID tagId = UUID.randomUUID();
        ZSetOperations.TypedTuple<String> tuple = new ZSetOperations.TypedTuple<String>() {
            public String getValue() { return tagId.toString(); }
            public Double getScore() { return 5.0; }
            public int compareTo(ZSetOperations.TypedTuple<String> o) { return 0; }
        };
        when(zSetOps.reverseRangeWithScores(eq("tag:hot"), eq(0L), eq(19L)))
                .thenReturn(Set.of(tuple));

        Tag tag = new Tag();
        tag.setId(tagId);
        tag.setName("java");
        tag.setSlug("java");
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));

        List<Tag> result = tagService.getHotTags(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("java");
    }

    @Test
    @DisplayName("getHotTags: cache miss queries DB and populates cache")
    @SuppressWarnings("unchecked")
    void getHotTags_cacheMiss_queriesDBAndPopulatesCache() {
        when(zSetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(null);

        Tag tag = new Tag();
        tag.setId(UUID.randomUUID());
        tag.setName("java");
        tag.setSlug("java");
        tag.setUsageCount(10);
        when(tagRepository.findTop20ByOrderByUsageCountDesc()).thenReturn(List.of(tag));

        List<Tag> result = tagService.getHotTags(20);

        assertThat(result).hasSize(1);
        verify(tagRepository).findTop20ByOrderByUsageCountDesc();
        verify(zSetOps).add(eq("tag:hot"), anyString(), any(Double.class));
    }

    @Test
    @DisplayName("getHotTags: cache miss 時 limit 參數截斷 DB 回傳結果")
    @SuppressWarnings("unchecked")
    void getHotTags_cacheMiss_respectsLimit() {
        when(zSetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(null);

        List<Tag> dbTags = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> {
                    Tag t = new Tag();
                    t.setId(UUID.randomUUID());
                    t.setName("tag-" + i);
                    t.setSlug("tag-" + i);
                    t.setUsageCount(20 - i);
                    return t;
                })
                .toList();
        when(tagRepository.findTop20ByOrderByUsageCountDesc()).thenReturn(dbTags);

        List<Tag> result = tagService.getHotTags(5);

        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("getTagDetail: cache hit returns from Hash")
    @SuppressWarnings("unchecked")
    void getTagDetail_cacheHit_returnsFromHash() {
        UUID id = UUID.randomUUID();
        when(hashOps.entries("tag:java")).thenReturn(Map.of(
                "id", id.toString(),
                "name", "java",
                "slug", "java",
                "usageCount", "5"
        ));

        TagDetailResponse result = tagService.getTagDetail("java");

        assertThat(result.getName()).isEqualTo("java");
        assertThat(result.getUsageCount()).isEqualTo(5);
        verify(tagRepository, never()).findBySlug(anyString());
    }

    @Test
    @DisplayName("getTagDetail: cache miss queries DB and populates Hash")
    @SuppressWarnings("unchecked")
    void getTagDetail_cacheMiss_queriesDBAndPopulatesHash() {
        when(hashOps.entries(anyString())).thenReturn(Map.of());

        Tag tag = new Tag();
        tag.setId(UUID.randomUUID());
        tag.setName("java");
        tag.setSlug("java");
        tag.setUsageCount(3);
        tag.setCreatedAt(LocalDateTime.now());
        when(tagRepository.findBySlug("java")).thenReturn(Optional.of(tag));

        TagDetailResponse result = tagService.getTagDetail("java");

        assertThat(result.getName()).isEqualTo("java");
        verify(tagRepository).findBySlug("java");
        verify(hashOps).putAll(eq("tag:java"), any());
    }

    @Test
    @DisplayName("getTagDetail: not found throws TAG_NOT_FOUND")
    @SuppressWarnings("unchecked")
    void getTagDetail_notFound_throwsBusinessException() {
        when(hashOps.entries(anyString())).thenReturn(Map.of());
        when(tagRepository.findBySlug("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tagService.getTagDetail("nonexistent"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(TagErrorCode.TAG_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("adminDeleteTag: usage count > 0 throws TAG_IN_USE")
    void adminDeleteTag_withUsageCount_throwsBusinessException() {
        UUID id = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(id);
        tag.setSlug("java");
        tag.setUsageCount(5);
        when(tagRepository.findById(id)).thenReturn(Optional.of(tag));

        assertThatThrownBy(() -> tagService.adminDeleteTag(id))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(TagErrorCode.TAG_IN_USE.getMessage());
    }

    @Test
    @DisplayName("adminDeleteTag: usage count 0 deletes tag")
    void adminDeleteTag_withZeroUsage_deletes() {
        UUID id = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(id);
        tag.setSlug("java");
        tag.setUsageCount(0);
        when(tagRepository.findById(id)).thenReturn(Optional.of(tag));
        when(articleTagRepository.countByTagId(id)).thenReturn(0);

        tagService.adminDeleteTag(id);

        verify(tagRepository).deleteById(id);
    }

    @Test
    @DisplayName("adminDeleteTag: usageCount=0 但 article_tags 有關聯 → throws TAG_IN_USE")
    void adminDeleteTag_zeroUsageButHasArticleTags_throwsBusinessException() {
        UUID id = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(id);
        tag.setSlug("java");
        tag.setUsageCount(0);
        when(tagRepository.findById(id)).thenReturn(Optional.of(tag));
        when(articleTagRepository.countByTagId(id)).thenReturn(1);

        assertThatThrownBy(() -> tagService.adminDeleteTag(id))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(TagErrorCode.TAG_IN_USE.getMessage());
    }

    @Test
    @DisplayName("adminDeleteTag: 正常刪除時清除 user_tag_follows")
    void adminDeleteTag_withZeroUsageAndNoArticleTags_cleansFollows() {
        UUID id = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(id);
        tag.setSlug("java");
        tag.setUsageCount(0);
        when(tagRepository.findById(id)).thenReturn(Optional.of(tag));
        when(articleTagRepository.countByTagId(id)).thenReturn(0);

        tagService.adminDeleteTag(id);

        verify(userTagFollowRepository).deleteByTagId(id);
        verify(tagRepository).deleteById(id);
    }

    // ─── suggest ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("suggest: Redis 回傳 null 時應回傳空列表")
    @SuppressWarnings("unchecked")
    void suggest_redisReturnsNull_returnsEmptyList() {
        when(zSetOps.rangeByLex(anyString(), any(Range.class), any(Limit.class))).thenReturn(null);

        List<String> result = tagService.suggest("ja", 10);

        assertThat(result).isEmpty();
    }

    // ─── getHotTags ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getHotTags: cache 為空 Set（非 null）時應從 DB 查詢")
    @SuppressWarnings("unchecked")
    void getHotTags_cacheEmptySet_queriesDB() {
        when(zSetOps.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(java.util.Collections.emptySet());

        Tag tag = new Tag();
        tag.setId(UUID.randomUUID());
        tag.setName("java");
        tag.setSlug("java");
        tag.setUsageCount(5);
        when(tagRepository.findTop20ByOrderByUsageCountDesc()).thenReturn(List.of(tag));

        List<Tag> result = tagService.getHotTags(10);

        assertThat(result).hasSize(1);
        verify(tagRepository).findTop20ByOrderByUsageCountDesc();
    }

    @Test
    @DisplayName("getHotTags: cache 中的 UUID 不存在於 DB 時應被過濾掉")
    @SuppressWarnings("unchecked")
    void getHotTags_cacheHitOrphanedUuid_filteredOut() {
        UUID orphanId = UUID.randomUUID();
        ZSetOperations.TypedTuple<String> tuple = new ZSetOperations.TypedTuple<String>() {
            public String getValue() { return orphanId.toString(); }
            public Double getScore() { return 3.0; }
            public int compareTo(ZSetOperations.TypedTuple<String> o) { return 0; }
        };
        when(zSetOps.reverseRangeWithScores(eq("tag:hot"), eq(0L), eq(9L)))
                .thenReturn(Set.of(tuple));
        when(tagRepository.findById(orphanId)).thenReturn(Optional.empty());

        List<Tag> result = tagService.getHotTags(10);

        assertThat(result).isEmpty();
    }

    // ─── getTagDetail ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getTagDetail: cache 回傳 null 時應從 DB 查詢")
    @SuppressWarnings("unchecked")
    void getTagDetail_cacheReturnsNull_queriesDB() {
        when(hashOps.entries(anyString())).thenReturn(null);

        Tag tag = new Tag();
        tag.setId(UUID.randomUUID());
        tag.setName("spring");
        tag.setSlug("spring");
        tag.setUsageCount(7);
        when(tagRepository.findBySlug("spring")).thenReturn(Optional.of(tag));

        TagDetailResponse result = tagService.getTagDetail("spring");

        assertThat(result.getName()).isEqualTo("spring");
        verify(tagRepository).findBySlug("spring");
    }

    @Test
    @DisplayName("getTagDetail: cache 中 usageCount 欄位缺失時預設為 0")
    @SuppressWarnings("unchecked")
    void getTagDetail_cacheHitMissingUsageCount_defaultsToZero() {
        UUID id = UUID.randomUUID();
        java.util.HashMap<Object, Object> cacheData = new java.util.HashMap<>();
        cacheData.put("id", id.toString());
        cacheData.put("name", "kotlin");
        cacheData.put("slug", "kotlin");
        // usageCount intentionally omitted
        when(hashOps.entries("tag:kotlin")).thenReturn(cacheData);

        TagDetailResponse result = tagService.getTagDetail("kotlin");

        assertThat(result.getUsageCount()).isEqualTo(0);
        verify(tagRepository, never()).findBySlug(anyString());
    }

    @Test
    @DisplayName("getTagDetail: cache miss 時 tag 欄位 null 值應存為空字串")
    @SuppressWarnings("unchecked")
    void getTagDetail_cacheMiss_nullFieldsStoredAsEmptyString() {
        when(hashOps.entries(anyString())).thenReturn(Map.of());

        Tag tag = new Tag();
        tag.setId(UUID.randomUUID());
        tag.setName("rust");
        tag.setSlug("rust");
        tag.setColor(null);
        tag.setIcon(null);
        tag.setDescription(null);
        tag.setUsageCount(0);
        when(tagRepository.findBySlug("rust")).thenReturn(Optional.of(tag));

        tagService.getTagDetail("rust");

        verify(hashOps).putAll(eq("tag:rust"), argThat(map -> {
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> m = (java.util.Map<String, String>) map;
            return "".equals(m.get("color")) && "".equals(m.get("icon")) && "".equals(m.get("description"));
        }));
    }

    // ─── adminUpdateTag ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("adminUpdateTag: tag 不存在時應拋出 TAG_NOT_FOUND")
    void adminUpdateTag_tagNotFound_throwsBusinessException() {
        UUID id = UUID.randomUUID();
        when(tagRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tagService.adminUpdateTag(id, new dowob.xyz.blog.module.tag.model.dto.UpdateTagRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(TagErrorCode.TAG_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("adminUpdateTag: 所有欄位不為 null 時應全部更新並清除快取")
    void adminUpdateTag_allFieldsProvided_updatesAndEvictsCache() {
        UUID id = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(id);
        tag.setSlug("go-lang");
        tag.setUsageCount(0);
        when(tagRepository.findById(id)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any(Tag.class))).thenReturn(tag);

        dowob.xyz.blog.module.tag.model.dto.UpdateTagRequest request = new dowob.xyz.blog.module.tag.model.dto.UpdateTagRequest();
        request.setColor("#ff0000");
        request.setIcon("fa-code");
        request.setDescription("Go programming language");

        Tag result = tagService.adminUpdateTag(id, request);

        assertThat(result.getColor()).isEqualTo("#ff0000");
        assertThat(result.getIcon()).isEqualTo("fa-code");
        assertThat(result.getDescription()).isEqualTo("Go programming language");
        verify(stringRedisTemplate).delete("tag:go-lang");
    }

    @Test
    @DisplayName("adminUpdateTag: 所有欄位為 null 時不更新任何屬性")
    void adminUpdateTag_allFieldsNull_noFieldsUpdated() {
        UUID id = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(id);
        tag.setSlug("python");
        tag.setColor("blue");
        tag.setIcon("fa-snake");
        tag.setDescription("original desc");
        tag.setUsageCount(0);
        when(tagRepository.findById(id)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any(Tag.class))).thenReturn(tag);

        dowob.xyz.blog.module.tag.model.dto.UpdateTagRequest request = new dowob.xyz.blog.module.tag.model.dto.UpdateTagRequest();
        // all fields null

        tagService.adminUpdateTag(id, request);

        assertThat(tag.getColor()).isEqualTo("blue");
        assertThat(tag.getIcon()).isEqualTo("fa-snake");
        assertThat(tag.getDescription()).isEqualTo("original desc");
    }

    // ─── adminDeleteTag ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("adminDeleteTag: tag 不存在時應拋出 TAG_NOT_FOUND")
    void adminDeleteTag_tagNotFound_throwsBusinessException() {
        UUID id = UUID.randomUUID();
        when(tagRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tagService.adminDeleteTag(id))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(TagErrorCode.TAG_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("adminDeleteTag: 成功刪除時應同時清除 Redis hash 快取與熱門 ZSet")
    void adminDeleteTag_successfulDelete_clearsRedisHashAndHotSet() {
        UUID id = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(id);
        tag.setSlug("scala");
        tag.setUsageCount(0);
        when(tagRepository.findById(id)).thenReturn(Optional.of(tag));
        when(articleTagRepository.countByTagId(id)).thenReturn(0);

        tagService.adminDeleteTag(id);

        verify(stringRedisTemplate).delete("tag:scala");
        verify(zSetOps).remove(eq("tag:hot"), eq(id.toString()));
        verify(tagRepository).deleteById(id);
    }
}
