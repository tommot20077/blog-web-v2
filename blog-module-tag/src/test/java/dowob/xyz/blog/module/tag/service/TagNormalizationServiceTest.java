package dowob.xyz.blog.module.tag.service;

import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import dowob.xyz.blog.common.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagNormalizationService 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TagNormalizationService 單元測試")
class TagNormalizationServiceTest {

    /**
     * Mock TagRepository
     */
    @Mock
    private TagRepository tagRepository;

    /**
     * Mock RedisTemplate
     */
    @Mock
    private RedisTemplate<String, String> redisTemplate;

    /**
     * Mock ZSetOperations
     */
    @Mock
    private ZSetOperations<String, String> zSetOps;

    /**
     * 待測服務（由 Mockito 注入 Mock 依賴）
     */
    @InjectMocks
    private TagNormalizationServiceImpl normalizationService;

    @Test
    @DisplayName("normalize: 輸入含前後空白，應自動修剪")
    void normalize_withTrailingSpaces_trims() {
        String result = normalizationService.normalize("  Java  ");
        assertThat(result).doesNotStartWith(" ").doesNotEndWith(" ");
    }

    @Test
    @DisplayName("normalize: 輸入大寫字母，應轉換為小寫")
    void normalize_withUpperCase_convertsToLower() {
        String result = normalizationService.normalize("JAVA");
        assertThat(result).isEqualTo("java");
    }

    @Test
    @DisplayName("findOrCreate: 標籤已存在時，應直接返回現有標籤")
    void findOrCreate_withExistingTag_returnsExisting() {
        Tag existingTag = new Tag();
        existingTag.setName("java");
        existingTag.setSlug("java");
        when(tagRepository.findBySlug("java")).thenReturn(Optional.of(existingTag));

        Tag result = normalizationService.findOrCreate("Java");

        assertThat(result).isSameAs(existingTag);
        verify(tagRepository, never()).save(any());
    }

    @Test
    @DisplayName("normalize: 輸入為 null，應拋出 BusinessException")
    void normalize_withNullInput_throwsBusinessException() {
        assertThatThrownBy(() -> normalizationService.normalize(null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("標籤名稱不能為空");
    }

    @Test
    @DisplayName("normalize: 輸入為空白字串，應拋出 BusinessException")
    void normalize_withBlankInput_throwsBusinessException() {
        assertThatThrownBy(() -> normalizationService.normalize("   "))
            .isInstanceOf(BusinessException.class)
            .hasMessage("標籤名稱不能為空");
    }

    @Test
    @DisplayName("findOrCreate: 輸入為空白，應拋出 BusinessException")
    void findOrCreate_withBlankInput_throwsBusinessException() {
        assertThatThrownBy(() -> normalizationService.findOrCreate(""))
            .isInstanceOf(BusinessException.class)
            .hasMessage("標籤名稱不能為空");
    }

    @Test
    @DisplayName("findOrCreate: 輸入只含特殊符號導致 Slug 為空，應拋出 BusinessException")
    void findOrCreate_withSpecialCharsOnly_throwsBusinessException() {
        assertThatThrownBy(() -> normalizationService.findOrCreate("!!!"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("標籤名稱不能為空");
    }

    @Test
    @DisplayName("findOrCreate: 標籤不存在時，應以 INSERT ... ON CONFLICT DO NOTHING 建立並加入 Redis 自動補全集合")
    void findOrCreate_withNewTag_createsAndAddsToRedis() {
        when(tagRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(tagRepository.insertIfAbsent(any(UUID.class), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        Tag result = normalizationService.findOrCreate("SpringBoot");

        verify(tagRepository).insertIfAbsent(eq(result.getId()), eq("springboot"), eq("springboot"), any(LocalDateTime.class));
        verify(tagRepository, never()).save(any());
        verify(zSetOps).add(eq("tag:autocomplete"), anyString(), anyDouble());
        assertThat(result.getName()).isEqualTo("springboot");
        assertThat(result.getSlug()).isEqualTo("springboot");
        assertThat(result.getId()).isNotNull();
    }

    @Test
    @DisplayName("findOrCreate: 併發下 INSERT 沒有新增任何列（別人先建立），應以 slug 重讀並回傳既有標籤，且不寫 Redis")
    void findOrCreate_insertAffectsNoRow_rereadsBySlugAndSkipsRedis() {
        Tag winner = new Tag();
        winner.setId(UUID.randomUUID());
        winner.setName("java");
        winner.setSlug("java");
        when(tagRepository.findBySlug("java")).thenReturn(Optional.empty(), Optional.of(winner));
        when(tagRepository.insertIfAbsent(any(UUID.class), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);

        Tag result = normalizationService.findOrCreate("Java");

        assertThat(result).isSameAs(winner);
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    @DisplayName("findOrCreate: INSERT 沒有新增列、以 slug 讀不到、但以 name 讀得到（只有 name 撞到），應回傳該標籤")
    void findOrCreate_conflictOnNameOnly_fallsBackToName() {
        Tag byName = new Tag();
        byName.setId(UUID.randomUUID());
        byName.setName("java");
        byName.setSlug("java-legacy");
        when(tagRepository.findBySlug("java")).thenReturn(Optional.empty(), Optional.empty());
        when(tagRepository.findByName("java")).thenReturn(Optional.of(byName));
        when(tagRepository.insertIfAbsent(any(UUID.class), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);

        assertThat(normalizationService.findOrCreate("Java")).isSameAs(byName);
    }

    @Test
    @DisplayName("findOrCreate: 衝突後對方剛建立的標籤又被刪掉（重讀不到），重試整個流程一次並成功建立")
    void findOrCreate_conflictThenWinnerDeleted_retriesWholeFlowOnce() {
        when(tagRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(tagRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(tagRepository.insertIfAbsent(any(UUID.class), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(0, 1);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        Tag result = normalizationService.findOrCreate("Java");

        verify(tagRepository, times(2)).insertIfAbsent(any(UUID.class), eq("java"), eq("java"), any(LocalDateTime.class));
        verify(zSetOps, times(1)).add(eq("tag:autocomplete"), eq("java"), anyDouble());
        assertThat(result.getSlug()).isEqualTo("java");
    }

    @Test
    @DisplayName("findOrCreate: 重試時第二輪的 slug 查詢已讀到別人建立的標籤，直接回傳、不再 INSERT")
    void findOrCreate_retryFindsExistingTag_returnsItWithoutSecondInsert() {
        Tag later = new Tag();
        later.setId(UUID.randomUUID());
        later.setSlug("java");
        when(tagRepository.findBySlug("java")).thenReturn(Optional.empty(), Optional.empty(), Optional.of(later));
        when(tagRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(tagRepository.insertIfAbsent(any(UUID.class), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);

        assertThat(normalizationService.findOrCreate("Java")).isSameAs(later);

        verify(tagRepository, times(1)).insertIfAbsent(any(UUID.class), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("findOrCreate: 重試後仍然衝突又讀不到（兩次都失敗），才拋 IllegalStateException，且只嘗試兩次")
    void findOrCreate_conflictTwiceAndNothingFound_throwsAfterTwoAttempts() {
        when(tagRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(tagRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(tagRepository.insertIfAbsent(any(UUID.class), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> normalizationService.findOrCreate("Java")).isInstanceOf(IllegalStateException.class);

        verify(tagRepository, times(2)).insertIfAbsent(any(UUID.class), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("findOrCreate: INSERT 沒有新增列卻怎麼也讀不到標籤（不應發生），拋 IllegalStateException 而非回傳 null")
    void findOrCreate_conflictButNothingFound_throwsIllegalState() {
        when(tagRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(tagRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(tagRepository.insertIfAbsent(any(UUID.class), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> normalizationService.findOrCreate("Java"))
                .isInstanceOf(IllegalStateException.class);
    }
}
