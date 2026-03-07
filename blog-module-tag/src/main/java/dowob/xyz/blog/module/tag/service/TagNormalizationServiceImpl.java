package dowob.xyz.blog.module.tag.service;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import dowob.xyz.blog.common.api.errorcode.TagErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import dowob.xyz.blog.module.tag.util.SlugUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 標籤正規化服務實作
 *
 * <p>
 * 正規化流程：修剪空白 → 轉小寫 → OpenCC 繁體轉簡體 → 生成 Slug →
 * 查找資料庫 → 已存在則返回，否則建立新標籤並加入 Redis 自動補全集合。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class TagNormalizationServiceImpl implements TagNormalizationService {

    /**
     * 標籤資料存取物件
     */
    private final TagRepository tagRepository;

    /**
     * Redis 操作模板（String 類型）
     */
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Redis 自動補全有序集合鍵名
     */
    private static final String AUTOCOMPLETE_KEY = "tag:autocomplete";

    @Override
    public String normalize(String rawName) {
        if (StringUtils.isBlank(rawName)) {
            throw new BusinessException(TagErrorCode.TAG_INVALID_NAME);
        }
        String trimmed = rawName.trim().toLowerCase();
        return ZhConverterUtil.toSimple(trimmed);
    }

    @Override
    public Tag findOrCreate(String rawName) {
        String normalized = normalize(rawName);
        String slug = SlugUtils.generateSlug(normalized);
        if (StringUtils.isBlank(slug)) {
            throw new BusinessException(TagErrorCode.TAG_INVALID_NAME);
        }

        Optional<Tag> existing = tagRepository.findBySlug(slug);
        if (existing.isPresent()) {
            return existing.get();
        }

        Tag newTag = new Tag();
        newTag.setId(UUID.randomUUID());
        newTag.setName(normalized);
        newTag.setSlug(slug);
        newTag.setCreatedAt(LocalDateTime.now());
        Tag saved = tagRepository.save(newTag);

        redisTemplate.opsForZSet().add(AUTOCOMPLETE_KEY, slug, 0.0);

        return saved;
    }
}
