package dowob.xyz.blog.module.tag.facade;

import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.service.TagNormalizationService;
import dowob.xyz.blog.module.tag.util.SlugUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
@Service
@RequiredArgsConstructor
public class TagFacadeImpl implements TagFacade {

    /** 標籤正規化服務（查找或建立標籤） */
    private final TagNormalizationService tagNormalizationService;

    /** 文章-標籤關聯 Repository */
    private final ArticleTagRepository articleTagRepository;

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

    @Override
    public void syncArticleTags(UUID articleUuid, List<UUID> tagIds) {
        /** 先清除舊關聯 */
        List<Tag> existingTags = articleTagRepository.findTagsByArticleId(articleUuid);
        existingTags.forEach(tag -> articleTagRepository.deleteByArticleIdAndTagId(articleUuid, tag.getId()));
        /** 建立新關聯 */
        if (tagIds == null) {
            tagIds = Collections.emptyList();
        }
        tagIds.forEach(tagId -> articleTagRepository.save(articleUuid, tagId));
    }

    @Override
    public void deleteArticleTags(UUID articleUuid) {
        List<Tag> existingTags = articleTagRepository.findTagsByArticleId(articleUuid);
        existingTags.forEach(tag -> articleTagRepository.deleteByArticleIdAndTagId(articleUuid, tag.getId()));
    }

    @Override
    public List<UUID> findTagIdsByArticleUuid(UUID articleUuid) {
        return articleTagRepository.findTagsByArticleId(articleUuid).stream()
                .map(Tag::getId)
                .collect(Collectors.toList());
    }
}
