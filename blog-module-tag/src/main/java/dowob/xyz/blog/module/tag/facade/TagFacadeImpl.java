package dowob.xyz.blog.module.tag.facade;

import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.service.TagNormalizationService;
import dowob.xyz.blog.module.tag.service.TagUsageRecalculator;
import dowob.xyz.blog.module.tag.util.SlugUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * TagFacade 實作
 *
 * <p>
 * 提供文章模組跨界操作標籤的統一入口。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
public class TagFacadeImpl implements TagFacade {

    /** 標籤正規化服務（查找或建立標籤） */
    private final TagNormalizationService tagNormalizationService;

    /** uuid 的無號位元組順序（與 Postgres uuid 的比較／ORDER BY 一致；Java 預設的 UUID.compareTo 是有號比較） */
    private static final Comparator<UUID> UNSIGNED_UUID_ORDER = (x, y) -> {
        int msb = Long.compareUnsigned(x.getMostSignificantBits(), y.getMostSignificantBits());
        return msb != 0 ? msb : Long.compareUnsigned(x.getLeastSignificantBits(), y.getLeastSignificantBits());
    };

    /** 文章-標籤關聯 Repository */
    private final ArticleTagRepository articleTagRepository;

    /**
     * 標籤使用計數重算（文章標籤被移除後，commit 之後對被移除的標籤重算）。
     *
     * <p>以 {@code @Lazy} 注入：article 模組的 {@code ArticleFacadeImpl} 依賴 {@code TagFacade}，
     * 而重算元件依賴 {@code ArticleFacade}，直接建構子注入會形成循環依賴。</p>
     */
    private final TagUsageRecalculator tagUsageRecalculator;

    /**
     * 建立 TagFacade 實作。
     *
     * @param tagNormalizationService 標籤正規化服務
     * @param articleTagRepository    文章-標籤關聯 Repository
     * @param tagUsageRecalculator    標籤使用計數重算元件（延遲注入，避免與 ArticleFacade 循環依賴）
     */
    public TagFacadeImpl(TagNormalizationService tagNormalizationService,
                         ArticleTagRepository articleTagRepository,
                         @Lazy TagUsageRecalculator tagUsageRecalculator) {
        this.tagNormalizationService = tagNormalizationService;
        this.articleTagRepository = articleTagRepository;
        this.tagUsageRecalculator = tagUsageRecalculator;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>建立順序固定（防死結）</b>：先把所有名稱正規化並算出 slug，依 (slug, 正規化後 name) 排出固定的處理順序，
     * 照該順序呼叫 {@link TagNormalizationService#findOrCreate}，結果寫回原本的索引位置，因此<b>回傳順序與輸入順序相同</b>。
     * 若不排序，兩個交易以相反順序建立同一組全新標籤時，會互相等待對方尚未提交的 INSERT 而死結
     * （PostgreSQL 40P01，其中一個交易被中止，發文回 500）；全部交易都用同一個順序取得鎖就不會形成環。</p>
     *
     * <p><b>行為改變</b>：blank 名稱（{@code normalize} 拋出 {@code BusinessException}）現在在建立任何標籤
     * <b>之前</b>就失敗；以前會先建立排在它前面的標籤，之後才失敗。整體仍在呼叫端交易內，結果上沒有差別。</p>
     */
    @Override
    public List<TagInfo> findOrCreateTags(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return Collections.emptyList();
        }
        int size = tagNames.size();
        String[] normalized = new String[size];
        String[] slugs = new String[size];
        for (int i = 0; i < size; i++) {
            normalized[i] = tagNormalizationService.normalize(tagNames.get(i));
            slugs[i] = SlugUtils.generateSlug(normalized[i]);
        }
        List<Integer> creationOrder = IntStream.range(0, size).boxed()
                .sorted(Comparator.<Integer, String>comparing(i -> slugs[i]).thenComparing(i -> normalized[i]))
                .toList();

        TagInfo[] result = new TagInfo[size];
        for (int index : creationOrder) {
            Tag tag = tagNormalizationService.findOrCreate(tagNames.get(index));
            result[index] = new TagInfo(tag.getId(), tag.getName(), tag.getSlug());
        }
        return Arrays.asList(result);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>插入順序固定</b>：依 tag id 的 uuid 位元組無號順序（與 Postgres {@code ORDER BY id} 一致）插入，
     * 讓持有 {@code tags} 列 {@code KEY SHARE}（FK 檢查）的取鎖順序與其他依 id 排序取鎖的交易一致。</p>
     *
     * <p><b>被移除的標籤</b>：新舊集合的差集（舊有、新無）沒有任何事件會帶它，於外層交易 commit 後
     * 對其重算使用計數（見 {@link #recalculateAfterCommit}）；新增的標籤維持由 {@code article.tagged} 事件重算。</p>
     */
    @Override
    public void syncArticleTags(UUID articleUuid, List<UUID> tagIds) {
        /** 先清除舊關聯 */
        List<Tag> existingTags = articleTagRepository.findTagsByArticleId(articleUuid);
        existingTags.forEach(tag -> articleTagRepository.deleteByArticleIdAndTagId(articleUuid, tag.getId()));
        /** 建立新關聯 */
        if (tagIds == null) {
            tagIds = Collections.emptyList();
        }
        List<UUID> insertOrder = tagIds.stream().distinct().sorted(UNSIGNED_UUID_ORDER).toList();
        insertOrder.forEach(tagId -> articleTagRepository.save(articleUuid, tagId));

        Set<UUID> removed = new LinkedHashSet<>();
        existingTags.forEach(tag -> removed.add(tag.getId()));
        removed.removeAll(insertOrder);
        recalculateAfterCommit(removed);
    }

    /**
     * {@inheritDoc}
     *
     * <p>原本掛的全部標籤皆為「被移除的標籤」，commit 後對其重算使用計數。</p>
     */
    @Override
    public void deleteArticleTags(UUID articleUuid) {
        List<Tag> existingTags = articleTagRepository.findTagsByArticleId(articleUuid);
        existingTags.forEach(tag -> articleTagRepository.deleteByArticleIdAndTagId(articleUuid, tag.getId()));
        Set<UUID> removed = new LinkedHashSet<>();
        existingTags.forEach(tag -> removed.add(tag.getId()));
        recalculateAfterCommit(removed);
    }

    /**
     * 於外層（文章）交易 commit 後重算被移除標籤的使用計數。
     *
     * <p>有交易同步時註冊 {@code afterCommit}（rollback 不會觸發，標籤關聯也跟著 rollback，無需重算）；
     * 沒有交易同步（auto-commit，刪除已生效）則立即重算。重算在自己的新交易內執行；
     * 失敗只記 WARN、不外拋——文章交易已 commit，計數由每日校正排程兜底。</p>
     *
     * @param removedTagIds 被移除的標籤 UUID；空集合不處理
     */
    private void recalculateAfterCommit(Set<UUID> removedTagIds) {
        if (removedTagIds.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recalculateRemovedTags(removedTagIds);
                }
            });
        } else {
            recalculateRemovedTags(removedTagIds);
        }
    }

    /**
     * 在新交易內重算被移除的標籤；失敗只記 WARN。
     *
     * @param removedTagIds 被移除的標籤 UUID
     */
    private void recalculateRemovedTags(Set<UUID> removedTagIds) {
        try {
            tagUsageRecalculator.recalculateInNewTransaction(removedTagIds);
        } catch (RuntimeException e) {
            log.warn("文章移除標籤後重算使用計數失敗（文章交易已 commit，計數將由每日校正修正）tagIds={}: {}",
                    removedTagIds, e.getMessage(), e);
        }
    }

    @Override
    public List<UUID> findTagIdsByArticleUuid(UUID articleUuid) {
        return articleTagRepository.findTagsByArticleId(articleUuid).stream()
                .map(Tag::getId)
                .collect(Collectors.toList());
    }
}
