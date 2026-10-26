package dowob.xyz.blog.module.tag.service;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import dowob.xyz.blog.common.api.errorcode.TagErrorCode;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
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
 * 查找資料庫 → 已存在則返回，否則以 {@code INSERT ... ON CONFLICT DO NOTHING} 建立新標籤並加入
 * Redis 自動補全集合。
 * </p>
 *
 * <p>
 * 併發安全（RACE-08）：先 {@code findBySlug} 再 {@code INSERT} 之間可能有別的交易建立同名／同 slug 標籤。
 * 以 {@code ON CONFLICT DO NOTHING} 建立時，衝突不會拋唯一鍵例外（PostgreSQL 的唯一鍵錯誤會使整個外層交易
 * 進入失敗狀態，導致發文整筆 rollback），而是新增 0 列，此時改以 slug 重讀既有標籤。
 * Redis 自動補全只在<b>實際新增</b>時寫入。
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

        UUID id = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now();
        int inserted = tagRepository.insertIfAbsent(id, normalized, slug, createdAt);
        if (inserted == 0) {
            /* 併發下別人先建立了同名／同 slug 的標籤（INSERT ... ON CONFLICT DO NOTHING 新增 0 列）：重讀既有標籤。
             * 衝突發生時對方交易必已提交（否則本次 INSERT 會等到對方結束），READ COMMITTED 下新的查詢讀得到。 */
            return tagRepository.findBySlug(slug)
                    .or(() -> tagRepository.findByName(normalized))
                    .orElseThrow(() -> new IllegalStateException("標籤建立衝突後仍讀不到既有標籤 slug=" + slug));
        }

        Tag created = new Tag();
        created.setId(id);
        created.setName(normalized);
        created.setSlug(slug);
        created.setCreatedAt(createdAt);
        redisTemplate.opsForZSet().add(RedisKeyConstant.TAG_AUTOCOMPLETE_KEY, slug, 0.0);

        return created;
    }
}
