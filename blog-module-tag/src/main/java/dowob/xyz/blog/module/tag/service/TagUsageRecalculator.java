package dowob.xyz.blog.module.tag.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 標籤使用計數重算（DATA-01／RACE-07）。
 *
 * <p><b>語意</b>：{@code tags.usage_count} = 該標籤下<b>已發布</b>文章數（決策紀錄 §6.3）。草稿、待審、
 * 駁回、下架的文章不計入；刪除標籤的守衛因此不能依賴此計數，改看 {@code article_tags} 是否有任何列。</p>
 *
 * <p><b>機制：重算而非遞增</b>。舊實作對每個事件做 {@code usage_count + 1}，既只增不減、又在重送與重複編輯時灌水，
 * 且 entity 讀改寫沒有鎖（併發 lost update）。現在每次對「受影響標籤」重算：</p>
 * <ol>
 *   <li>以 {@code SELECT ... FOR UPDATE} 依 id 升冪鎖定標籤列（同一標籤的併發重算被序列化；
 *       取鎖順序一致，多標籤不會死結）；</li>
 *   <li>從自己的 {@code article_tags} 取出該標籤的文章 UUID，委派
 *       {@link ArticleFacade#filterPublishedUuids} 篩出已發布者（{@code articles} 屬 article 模組，
 *       不跨模組查表），其數量即新計數；</li>
 *   <li>與舊值不同才 {@code UPDATE}（寫絕對值），並清除該標籤的詳情快取；</li>
 *   <li>所有 DB 寫入完成後，才以 {@code ZADD} 把 Redis 熱門分數<b>設為</b>新計數
 *       （Redis 無法隨交易 rollback，先 DB 後 Redis；任何失敗都往外拋，由呼叫端重試，重算天然冪等）。</li>
 * </ol>
 * 因為第 1 步先鎖再讀，後到的重算一定讀到先到者 commit 之後的狀態，不會被舊快照覆寫。
 *
 * <p><b>規模界限</b>：重算要把單一標籤的所有文章 UUID 載入記憶體並經 facade 分批查詢，成本隨該標籤的文章數線性成長。
 * 設計假設單一標籤的文章數在約 1 萬篇以內；超過時應改為<b>增量計數＋定期校正</b>
 * （事件只加減 1，每日校正排程 {@code TagUsageReconciliationJob} 負責收斂漂移），屆時由本類別改寫。</p>
 *
 * <p>交易：兩個公開方法皆為 {@code @Transactional}（REQUIRED），加入呼叫端既有交易
 * （例如 {@code ConsumerExecutor.executeIdempotent} 的冪等交易），沒有交易時自己開一個。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
public class TagUsageRecalculator {

    /** 標籤資料存取（列鎖、寫入計數） */
    private final TagRepository tagRepository;

    /** 文章-標籤關聯資料存取（取標籤下的文章 UUID） */
    private final ArticleTagRepository articleTagRepository;

    /** 文章跨模組 facade（篩出已發布文章） */
    private final ArticleFacade articleFacade;

    /** Redis 操作模板（熱門標籤 ZSet、標籤詳情快取） */
    private final RedisTemplate<String, String> stringRedisTemplate;

    /**
     * 建立重算元件。
     *
     * @param tagRepository        標籤資料存取
     * @param articleTagRepository 文章-標籤關聯資料存取
     * @param articleFacade        文章跨模組 facade
     * @param stringRedisTemplate  Redis 操作模板（String 類型）
     */
    public TagUsageRecalculator(TagRepository tagRepository, ArticleTagRepository articleTagRepository,
                                ArticleFacade articleFacade, RedisTemplate<String, String> stringRedisTemplate) {
        this.tagRepository = tagRepository;
        this.articleTagRepository = articleTagRepository;
        this.articleFacade = articleFacade;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 重算指定標籤的使用計數（已發布文章數），並以 ZADD 設定 Redis 熱門分數。
     *
     * <p>null、空集合直接返回；重複與 null 的 id 先去除；不存在的標籤靜默略過。</p>
     *
     * @param tagIds 受影響的標籤 UUID
     * @return 計數被修正（與舊值不同）的標籤數
     */
    @Transactional
    public int recalculate(Collection<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return 0;
        }
        Set<UUID> distinctIds = new LinkedHashSet<>();
        for (UUID tagId : tagIds) {
            if (tagId != null) {
                distinctIds.add(tagId);
            }
        }
        if (distinctIds.isEmpty()) {
            return 0;
        }

        List<Tag> tags = tagRepository.findAllByIdInForUpdate(distinctIds);
        int corrected = 0;
        List<Integer> counts = new ArrayList<>(tags.size());
        for (Tag tag : tags) {
            int publishedCount = countPublishedArticles(tag.getId());
            if (publishedCount != tag.getUsageCount()) {
                tagRepository.updateUsageCount(tag.getId(), publishedCount);
                stringRedisTemplate.delete(RedisKeyConstant.getTagDetailKey(tag.getSlug()));
                corrected++;
                log.info("標籤使用計數已修正 tagId={} {} -> {}", tag.getId(), tag.getUsageCount(), publishedCount);
            }
            counts.add(publishedCount);
        }

        /* Redis 無法隨交易 rollback：先完成全部 DB 寫入，才一次設定熱門分數 */
        for (int i = 0; i < tags.size(); i++) {
            stringRedisTemplate.opsForZSet()
                    .add(RedisKeyConstant.TAG_HOT_KEY, tags.get(i).getId().toString(), counts.get(i));
        }
        return corrected;
    }

    /**
     * 重算指定文章目前所掛標籤的使用計數（文章發布、更新、下架事件使用）。
     *
     * @param articleUuid 文章公開 UUID；null 直接返回
     * @return 計數被修正的標籤數
     */
    @Transactional
    public int recalculateForArticle(UUID articleUuid) {
        if (articleUuid == null) {
            return 0;
        }
        List<UUID> tagIds = articleTagRepository.findTagIdsByArticleId(articleUuid);
        return recalculate(tagIds);
    }

    /**
     * 計算某標籤下已發布的文章數。
     *
     * @param tagId 標籤 UUID
     * @return 已發布文章數
     */
    private int countPublishedArticles(UUID tagId) {
        List<UUID> articleIds = articleTagRepository.findArticleIdsByTagId(tagId);
        if (articleIds.isEmpty()) {
            return 0;
        }
        Set<UUID> distinct = new HashSet<>(articleIds);
        Set<UUID> published = articleFacade.filterPublishedUuids(distinct);
        return (int) distinct.stream().filter(id -> published != null && published.contains(id)).count();
    }
}
