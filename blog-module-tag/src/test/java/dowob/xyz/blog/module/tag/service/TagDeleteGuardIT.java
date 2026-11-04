package dowob.xyz.blog.module.tag.service;

import dowob.xyz.blog.common.api.errorcode.TagErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 刪除標籤守衛整合測試（DATA-01，真實 Postgres）：守衛看 {@code article_tags} 是否有任何列（含草稿），
 * 不再依賴 {@code usage_count} 計數器。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("刪除標籤守衛 整合測試")
class TagDeleteGuardIT extends TagCleanupITSupport {

    @Autowired private TagService tagService;

    @Test
    @DisplayName("還有草稿掛著（usage_count=0，因草稿不計入已發布數）：刪除被拒 TAG_IN_USE，標籤保留")
    void adminDeleteTag_draftStillAttached_isRejected() {
        UUID tag = insertTag("java", 0);
        insertArticleTag(UUID.randomUUID(), tag);

        assertThatThrownBy(() -> tagService.adminDeleteTag(tag))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(TagErrorCode.TAG_IN_USE.getMessage());

        assertThat(countTags()).isEqualTo(1);
    }

    @Test
    @DisplayName("沒有任何 article_tags（即使 usage_count 殘留舊的灌水值）：允許刪除")
    void adminDeleteTag_noArticleTags_isAllowedEvenWithStaleCounter() {
        UUID tag = insertTag("java", 12);

        tagService.adminDeleteTag(tag);

        assertThat(countTags()).isZero();
    }
}
