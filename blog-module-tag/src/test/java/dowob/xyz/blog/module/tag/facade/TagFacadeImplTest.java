package dowob.xyz.blog.module.tag.facade;

import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.service.TagNormalizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagFacadeImpl 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TagFacadeImpl 單元測試")
class TagFacadeImplTest {

    @Mock
    private TagNormalizationService tagNormalizationService;

    @Mock
    private ArticleTagRepository articleTagRepository;

    @InjectMocks
    private TagFacadeImpl tagFacadeImpl;

    /**
     * 建立測試用 Tag，UUID、name、slug 均填入
     */
    private Tag buildTag(UUID id, String name, String slug) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setName(name);
        tag.setSlug(slug);
        return tag;
    }

    /** ----------------------------------------------------------------------- */
    /** findOrCreateTags                                                        */
    /** ----------------------------------------------------------------------- */

    @Nested
    @DisplayName("findOrCreateTags")
    class FindOrCreateTagsTests {

        @Test
        @DisplayName("正常：傳入兩個 tagName，各呼叫一次 findOrCreate，回傳兩個 TagInfo")
        void findOrCreateTags_callsFindOrCreateForEachName() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            Tag tag1 = buildTag(id1, "Spring", "spring");
            Tag tag2 = buildTag(id2, "Java", "java");

            when(tagNormalizationService.findOrCreate("Spring")).thenReturn(tag1);
            when(tagNormalizationService.findOrCreate("Java")).thenReturn(tag2);

            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(List.of("Spring", "Java"));

            verify(tagNormalizationService).findOrCreate("Spring");
            verify(tagNormalizationService).findOrCreate("Java");
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("正常：回傳的 TagInfo 包含正確的 id、name、slug")
        void findOrCreateTags_returnsTagInfoWithCorrectFields() {
            UUID id1 = UUID.randomUUID();
            Tag tag1 = buildTag(id1, "Spring", "spring");
            when(tagNormalizationService.findOrCreate("Spring")).thenReturn(tag1);

            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(List.of("Spring"));

            assertThat(result).hasSize(1);
            TagInfo info = result.get(0);
            assertThat(info.id()).isEqualTo(id1);
            assertThat(info.name()).isEqualTo("Spring");
            assertThat(info.slug()).isEqualTo("spring");
        }

        @Test
        @DisplayName("邊界：空列表傳入，回傳空列表，findOrCreate 不被呼叫")
        void findOrCreateTags_emptyInput_returnsEmptyList() {
            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(List.of());

            assertThat(result).isEmpty();
            verify(tagNormalizationService, never()).findOrCreate(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("防護：null 傳入，回傳空列表，不拋 NPE")
        void findOrCreateTags_nullInput_returnsEmptyList() {
            List<TagInfo> result = tagFacadeImpl.findOrCreateTags(null);

            assertThat(result).isEmpty();
            verify(tagNormalizationService, never()).findOrCreate(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /** ----------------------------------------------------------------------- */
    /** syncArticleTags                                                         */
    /** ----------------------------------------------------------------------- */

    @Nested
    @DisplayName("syncArticleTags")
    class SyncArticleTagsTests {

        @Test
        @DisplayName("正常：有舊標籤時，先刪除舊標籤，再新增新標籤")
        void syncArticleTags_deletesOldAndSavesNew() {
            UUID articleUuid = UUID.randomUUID();
            UUID existingTagId = UUID.randomUUID();
            Tag existingTag = buildTag(existingTagId, "OldTag", "old-tag");
            when(articleTagRepository.findTagsByArticleId(articleUuid)).thenReturn(List.of(existingTag));

            UUID newTagId = UUID.randomUUID();
            tagFacadeImpl.syncArticleTags(articleUuid, List.of(newTagId));

            verify(articleTagRepository).deleteByArticleIdAndTagId(articleUuid, existingTagId);
            verify(articleTagRepository).save(articleUuid, newTagId);
        }

        @Test
        @DisplayName("正常：多個舊標籤，每個都要刪除；多個新標籤，每個都要新增")
        void syncArticleTags_deletesAllOldAndSavesAllNew() {
            UUID articleUuid = UUID.randomUUID();
            UUID oldId1 = UUID.randomUUID();
            UUID oldId2 = UUID.randomUUID();
            Tag oldTag1 = buildTag(oldId1, "Tag1", "tag-1");
            Tag oldTag2 = buildTag(oldId2, "Tag2", "tag-2");
            when(articleTagRepository.findTagsByArticleId(articleUuid)).thenReturn(List.of(oldTag1, oldTag2));

            UUID newId1 = UUID.randomUUID();
            UUID newId2 = UUID.randomUUID();
            tagFacadeImpl.syncArticleTags(articleUuid, List.of(newId1, newId2));

            verify(articleTagRepository).deleteByArticleIdAndTagId(articleUuid, oldId1);
            verify(articleTagRepository).deleteByArticleIdAndTagId(articleUuid, oldId2);
            verify(articleTagRepository).save(articleUuid, newId1);
            verify(articleTagRepository).save(articleUuid, newId2);
        }

        @Test
        @DisplayName("邊界：無舊標籤時，不呼叫 deleteByArticleIdAndTagId，仍新增新標籤")
        void syncArticleTags_noExistingTags_onlySaves() {
            UUID articleUuid = UUID.randomUUID();
            when(articleTagRepository.findTagsByArticleId(articleUuid)).thenReturn(List.of());

            UUID newTagId = UUID.randomUUID();
            tagFacadeImpl.syncArticleTags(articleUuid, List.of(newTagId));

            verify(articleTagRepository, never()).deleteByArticleIdAndTagId(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            verify(articleTagRepository).save(articleUuid, newTagId);
        }

        @Test
        @DisplayName("防護：tagIds 為 null 時，只清除舊關聯，不拋 NPE")
        void syncArticleTags_nullTagIds_deletesOldAndDoesNotThrow() {
            UUID articleUuid = UUID.randomUUID();
            UUID existingTagId = UUID.randomUUID();
            Tag existingTag = buildTag(existingTagId, "OldTag", "old-tag");
            when(articleTagRepository.findTagsByArticleId(articleUuid)).thenReturn(List.of(existingTag));

            tagFacadeImpl.syncArticleTags(articleUuid, null);

            verify(articleTagRepository).deleteByArticleIdAndTagId(articleUuid, existingTagId);
            verify(articleTagRepository, never()).save(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    /** ----------------------------------------------------------------------- */
    /** deleteArticleTags                                                       */
    /** ----------------------------------------------------------------------- */

    @Nested
    @DisplayName("deleteArticleTags")
    class DeleteArticleTagsTests {

        @Test
        @DisplayName("正常：有兩個標籤，每個都呼叫 deleteByArticleIdAndTagId")
        void deleteArticleTags_deletesAll() {
            UUID articleUuid = UUID.randomUUID();
            UUID tagId1 = UUID.randomUUID();
            UUID tagId2 = UUID.randomUUID();
            Tag tag1 = buildTag(tagId1, "Tag1", "tag-1");
            Tag tag2 = buildTag(tagId2, "Tag2", "tag-2");
            when(articleTagRepository.findTagsByArticleId(articleUuid)).thenReturn(List.of(tag1, tag2));

            tagFacadeImpl.deleteArticleTags(articleUuid);

            verify(articleTagRepository).deleteByArticleIdAndTagId(articleUuid, tagId1);
            verify(articleTagRepository).deleteByArticleIdAndTagId(articleUuid, tagId2);
            verify(articleTagRepository, times(2)).deleteByArticleIdAndTagId(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("邊界：無標籤時，不呼叫 deleteByArticleIdAndTagId")
        void deleteArticleTags_noTags_doesNothing() {
            UUID articleUuid = UUID.randomUUID();
            when(articleTagRepository.findTagsByArticleId(articleUuid)).thenReturn(List.of());

            tagFacadeImpl.deleteArticleTags(articleUuid);

            verify(articleTagRepository, never()).deleteByArticleIdAndTagId(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }
}
