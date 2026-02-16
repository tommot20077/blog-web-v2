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

import java.util.Optional;

import dowob.xyz.blog.common.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    @DisplayName("findOrCreate: 標籤不存在時，應建立新標籤並加入 Redis 自動補全集合")
    void findOrCreate_withNewTag_createsAndAddsToRedis() {
        when(tagRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(tagRepository.save(any(Tag.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        Tag result = normalizationService.findOrCreate("SpringBoot");

        verify(tagRepository).save(any(Tag.class));
        verify(zSetOps).add(eq("tag:autocomplete"), anyString(), anyDouble());
        assertThat(result).isNotNull();
    }
}
