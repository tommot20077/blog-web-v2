package dowob.xyz.blog.common.api.enums;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArticleStatus 單元測試
 *
 * <p>驗證 {@code isPubliclyVisible()} 方法，僅 PUBLISHED 狀態應回傳 true。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleStatus 單元測試")
class ArticleStatusTest {

    @Test
    @DisplayName("PUBLISHED 狀態 isPubliclyVisible() 應回傳 true")
    void isPubliclyVisible_published_returnsTrue() {
        assertThat(ArticleStatus.PUBLISHED.isPubliclyVisible()).isTrue();
    }

    @Test
    @DisplayName("DRAFT 狀態 isPubliclyVisible() 應回傳 false")
    void isPubliclyVisible_draft_returnsFalse() {
        assertThat(ArticleStatus.DRAFT.isPubliclyVisible()).isFalse();
    }

    @Test
    @DisplayName("PENDING_REVIEW 狀態 isPubliclyVisible() 應回傳 false")
    void isPubliclyVisible_pendingReview_returnsFalse() {
        assertThat(ArticleStatus.PENDING_REVIEW.isPubliclyVisible()).isFalse();
    }

    @Test
    @DisplayName("ARCHIVED 狀態 isPubliclyVisible() 應回傳 false")
    void isPubliclyVisible_archived_returnsFalse() {
        assertThat(ArticleStatus.ARCHIVED.isPubliclyVisible()).isFalse();
    }

    @Test
    @DisplayName("REJECTED 狀態 isPubliclyVisible() 應回傳 false")
    void isPubliclyVisible_rejected_returnsFalse() {
        assertThat(ArticleStatus.REJECTED.isPubliclyVisible()).isFalse();
    }

    @Test
    @DisplayName("所有非 PUBLISHED 狀態均不公開可見")
    void isPubliclyVisible_allNonPublished_allReturnFalse() {
        for (ArticleStatus status : ArticleStatus.values()) {
            if (status != ArticleStatus.PUBLISHED) {
                assertThat(status.isPubliclyVisible())
                        .as("狀態 %s 不應公開可見", status)
                        .isFalse();
            }
        }
    }

    // ── String overload：跨模組以 status name（String）判斷可見性，收斂政策於此 ──

    @Test
    @DisplayName("isPubliclyVisible(\"PUBLISHED\") 應回傳 true")
    void isPubliclyVisible_publishedName_returnsTrue() {
        assertThat(ArticleStatus.isPubliclyVisible("PUBLISHED")).isTrue();
    }

    @Test
    @DisplayName("String overload 與 enum instance 方法對每個狀態結果一致")
    void isPubliclyVisible_byName_matchesInstanceMethod() {
        for (ArticleStatus status : ArticleStatus.values()) {
            assertThat(ArticleStatus.isPubliclyVisible(status.name()))
                    .as("狀態 %s 的 String overload 應與 instance 方法一致", status)
                    .isEqualTo(status.isPubliclyVisible());
        }
    }

    @Test
    @DisplayName("null 或未知 status name 一律不公開可見（不拋例外）")
    void isPubliclyVisible_nullOrUnknownName_returnsFalse() {
        assertThat(ArticleStatus.isPubliclyVisible(null)).isFalse();
        assertThat(ArticleStatus.isPubliclyVisible("NOT_A_STATUS")).isFalse();
        assertThat(ArticleStatus.isPubliclyVisible("")).isFalse();
    }

    // ── 作者刪除帳號時的去留（DATA-02，Q2 定案：保留已發布、刪除草稿／待審）──

    @Test
    @DisplayName("DRAFT、PENDING_REVIEW、REJECTED 隨作者刪帳一併移除")
    void isRemovedWithAuthorAccount_unpublishedStates_returnTrue() {
        assertThat(ArticleStatus.DRAFT.isRemovedWithAuthorAccount()).isTrue();
        assertThat(ArticleStatus.PENDING_REVIEW.isRemovedWithAuthorAccount()).isTrue();
        assertThat(ArticleStatus.REJECTED.isRemovedWithAuthorAccount()).isTrue();
    }

    @Test
    @DisplayName("PUBLISHED 保留（作者顯示自動變成匿名暱稱）；ARCHIVED 保留（下架可能涉及法務留存，不由刪帳連動銷毀）")
    void isRemovedWithAuthorAccount_publishedAndArchived_returnFalse() {
        assertThat(ArticleStatus.PUBLISHED.isRemovedWithAuthorAccount()).isFalse();
        assertThat(ArticleStatus.ARCHIVED.isRemovedWithAuthorAccount()).isFalse();
    }

    @Test
    @DisplayName("removedWithAuthorAccount String overload 與 enum instance 方法對每個狀態結果一致")
    void isRemovedWithAuthorAccount_byName_matchesInstanceMethod() {
        for (ArticleStatus status : ArticleStatus.values()) {
            assertThat(ArticleStatus.isRemovedWithAuthorAccount(status.name()))
                    .as("狀態 %s 的 String overload 應與 instance 方法一致", status)
                    .isEqualTo(status.isRemovedWithAuthorAccount());
        }
    }

    @Test
    @DisplayName("null 或未知 status name 視為「不移除」（保守：無法確認就不銷毀資料，不拋例外）")
    void isRemovedWithAuthorAccount_nullOrUnknownName_returnsFalse() {
        assertThat(ArticleStatus.isRemovedWithAuthorAccount(null)).isFalse();
        assertThat(ArticleStatus.isRemovedWithAuthorAccount("NOT_A_STATUS")).isFalse();
        assertThat(ArticleStatus.isRemovedWithAuthorAccount("")).isFalse();
    }
}
