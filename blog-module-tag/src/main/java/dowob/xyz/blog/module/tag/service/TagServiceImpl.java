package dowob.xyz.blog.module.tag.service;

import dowob.xyz.blog.common.api.errorcode.TagErrorCode;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.model.dto.TagDetailResponse;
import dowob.xyz.blog.module.tag.model.dto.UpdateTagRequest;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import dowob.xyz.blog.module.tag.repository.UserTagFollowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 標籤服務實作
 *
 * <p>
 * 整合 Redis 快取（熱門標籤 ZSet、標籤詳情 Hash、自動補全 ZSet）與資料庫，
 * 提供高效的標籤查詢及管理功能。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    /**
     * 標籤資料存取物件
     */
    private final TagRepository tagRepository;

    /**
     * 使用者標籤追蹤資料存取物件
     */
    private final UserTagFollowRepository userTagFollowRepository;

    /**
     * Redis 操作模板（String 類型）
     */
    private final RedisTemplate<String, String> stringRedisTemplate;

    /**
     * 文章標籤關聯資料存取物件（用於刪除前 FK 檢查）
     */
    private final ArticleTagRepository articleTagRepository;

    /**
     * 熱門標籤快取過期時間（小時）
     */
    private static final long HOT_TAGS_TTL_HOURS = 1L;

    /**
     * 標籤詳情快取過期時間（小時）
     */
    private static final long TAG_DETAIL_TTL_HOURS = 24L;

    @Override
    public List<String> suggest(String prefix, int limit) {
        Range<String> range = Range.of(
                Range.Bound.inclusive(prefix),
                Range.Bound.inclusive(prefix + "\uffff")
        );
        Limit redisLimit = Limit.limit().count(limit);
        Set<String> results = stringRedisTemplate.opsForZSet().rangeByLex(RedisKeyConstant.TAG_AUTOCOMPLETE_KEY, range, redisLimit);
        if (results == null) {
            return List.of();
        }
        return new ArrayList<>(results);
    }

    @Override
    public List<Tag> getHotTags(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        Set<ZSetOperations.TypedTuple<String>> cached = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(RedisKeyConstant.TAG_HOT_KEY, 0L, (long) limit - 1);

        if (cached != null && !cached.isEmpty()) {
            return cached.stream()
                    .map(tuple -> {
                        UUID id = UUID.fromString(tuple.getValue());
                        return tagRepository.findById(id).orElse(null);
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        List<Tag> tags = tagRepository.findTop20ByOrderByUsageCountDesc();
        tags.forEach(tag -> stringRedisTemplate.opsForZSet()
                .add(RedisKeyConstant.TAG_HOT_KEY, tag.getId().toString(), tag.getUsageCount()));
        stringRedisTemplate.expire(RedisKeyConstant.TAG_HOT_KEY, HOT_TAGS_TTL_HOURS, TimeUnit.HOURS);
        return tags.subList(0, Math.min(limit, tags.size()));
    }

    @Override
    public TagDetailResponse getTagDetail(String slug) {
        String cacheKey = RedisKeyConstant.getTagDetailKey(slug);
        Map<Object, Object> cached = stringRedisTemplate.opsForHash().entries(cacheKey);

        if (cached != null && !cached.isEmpty()) {
            TagDetailResponse response = new TagDetailResponse();
            response.setId(UUID.fromString((String) cached.get("id")));
            response.setName((String) cached.get("name"));
            response.setSlug((String) cached.get("slug"));
            response.setColor((String) cached.get("color"));
            response.setIcon((String) cached.get("icon"));
            response.setDescription((String) cached.get("description"));
            String countStr = (String) cached.get("usageCount");
            response.setUsageCount(countStr != null ? Integer.parseInt(countStr) : 0);
            return response;
        }

        Tag tag = tagRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(TagErrorCode.TAG_NOT_FOUND));

        Map<String, String> hashData = new HashMap<>();
        hashData.put("id", tag.getId().toString());
        hashData.put("name", tag.getName());
        hashData.put("slug", tag.getSlug());
        hashData.put("color", tag.getColor() != null ? tag.getColor() : "");
        hashData.put("icon", tag.getIcon() != null ? tag.getIcon() : "");
        hashData.put("description", tag.getDescription() != null ? tag.getDescription() : "");
        hashData.put("usageCount", String.valueOf(tag.getUsageCount()));
        stringRedisTemplate.opsForHash().putAll(cacheKey, hashData);
        stringRedisTemplate.expire(cacheKey, TAG_DETAIL_TTL_HOURS, TimeUnit.HOURS);

        TagDetailResponse response = new TagDetailResponse();
        response.setId(tag.getId());
        response.setName(tag.getName());
        response.setSlug(tag.getSlug());
        response.setColor(tag.getColor());
        response.setIcon(tag.getIcon());
        response.setDescription(tag.getDescription());
        response.setUsageCount(tag.getUsageCount());
        return response;
    }

    @Override
    public void followTag(UUID tagId, UUID userId) {
        userTagFollowRepository.follow(userId, tagId);
    }

    @Override
    public void unfollowTag(UUID tagId, UUID userId) {
        userTagFollowRepository.unfollow(userId, tagId);
    }

    @Override
    public Tag adminUpdateTag(UUID id, UpdateTagRequest request) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new BusinessException(TagErrorCode.TAG_NOT_FOUND));
        if (request.getColor() != null) {
            tag.setColor(request.getColor());
        }
        if (request.getIcon() != null) {
            tag.setIcon(request.getIcon());
        }
        if (request.getDescription() != null) {
            tag.setDescription(request.getDescription());
        }
        Tag saved = tagRepository.save(tag);
        stringRedisTemplate.delete(RedisKeyConstant.getTagDetailKey(tag.getSlug()));
        return saved;
    }

    @Override
    @Transactional
    public void adminDeleteTag(UUID id) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new BusinessException(TagErrorCode.TAG_NOT_FOUND));
        if (tag.getUsageCount() > 0) {
            throw new BusinessException(TagErrorCode.TAG_IN_USE);
        }
        if (articleTagRepository.countByTagId(id) > 0) {
            throw new BusinessException(TagErrorCode.TAG_IN_USE);
        }
        userTagFollowRepository.deleteByTagId(id);
        stringRedisTemplate.delete(RedisKeyConstant.getTagDetailKey(tag.getSlug()));
        stringRedisTemplate.opsForZSet().remove(RedisKeyConstant.TAG_HOT_KEY, tag.getId().toString());
        tagRepository.deleteById(id);
    }
}
