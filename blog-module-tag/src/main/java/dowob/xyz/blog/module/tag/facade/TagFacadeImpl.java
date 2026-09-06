package dowob.xyz.blog.module.tag.facade;

import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.service.TagNormalizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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

    @Override
    public List<TagInfo> findOrCreateTags(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return Collections.emptyList();
        }
        return tagNames.stream()
                .map(tagNormalizationService::findOrCreate)
                .map(tag -> new TagInfo(tag.getId(), tag.getName(), tag.getSlug()))
                .collect(Collectors.toList());
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
