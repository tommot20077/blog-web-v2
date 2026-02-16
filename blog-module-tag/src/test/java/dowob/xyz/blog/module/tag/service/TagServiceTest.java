package dowob.xyz.blog.module.tag.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.model.TagErrorCode;
import dowob.xyz.blog.module.tag.model.dto.TagDetailResponse;
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
        tagService = new TagServiceImpl(tagRepository, userTagFollowRepository, stringRedisTemplate);
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

        tagService.adminDeleteTag(id);

        verify(tagRepository).deleteById(id);
    }
}
