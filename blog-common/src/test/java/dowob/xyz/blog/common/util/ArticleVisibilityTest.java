package dowob.xyz.blog.common.util;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleVisibility} 單元測試。
 *
 * <p>Z1 起只有 UUID 版（Long 版已移除）。政策：PUBLISHED 任何人可讀；其餘狀態僅作者本人與 ADMIN 可讀。</p>
 *
 * @author Yuan
 * @version 2.0（Z1：移除 Long 版）
 */
@DisplayName("ArticleVisibility 可見性判斷")
class ArticleVisibilityTest {

    private static final UUID AUTHOR_UUID = UUID.randomUUID();
    private static final UUID OTHER_UUID = UUID.randomUUID();

    @Nested
    @DisplayName("UUID 版")
    class UuidVersion {

        @Test
        @DisplayName("PUBLISHED → 匿名（viewerUuid 為 null）也可讀")
        void published_anonymous_readable() {
            assertThat(ArticleVisibility.isReadableBy("PUBLISHED", AUTHOR_UUID, (UUID) null, false)).isTrue();
        }

        @Test
        @DisplayName("非公開狀態 → 作者本人可讀")
        void nonPublished_author_readable() {
            assertThat(ArticleVisibility.isReadableBy("DRAFT", AUTHOR_UUID, AUTHOR_UUID, false)).isTrue();
        }

        @Test
        @DisplayName("非公開狀態 → 他人不可讀")
        void nonPublished_other_notReadable() {
            assertThat(ArticleVisibility.isReadableBy("PENDING_REVIEW", AUTHOR_UUID, OTHER_UUID, false)).isFalse();
        }

        @Test
        @DisplayName("非公開狀態 → ADMIN 可讀")
        void nonPublished_admin_readable() {
            assertThat(ArticleVisibility.isReadableBy("ARCHIVED", AUTHOR_UUID, OTHER_UUID, true)).isTrue();
        }

        @Test
        @DisplayName("非公開狀態 → 匿名不可讀")
        void nonPublished_anonymous_notReadable() {
            assertThat(ArticleVisibility.isReadableBy("DRAFT", AUTHOR_UUID, (UUID) null, false)).isFalse();
        }

        @Test
        @DisplayName("作者 UUID 為 null 且檢視者為 null → 非公開狀態不可讀（Objects.equals 不得讓 null==null 放行）")
        void nonPublished_bothNull_notReadable() {
            assertThat(ArticleVisibility.isReadableBy("DRAFT", (UUID) null, (UUID) null, false)).isFalse();
        }

        @Test
        @DisplayName("狀態為 null 或未知字串 → 視為不公開，僅作者與 ADMIN 可讀")
        void nullOrUnknownStatus_treatedAsNonPublic() {
            assertThat(ArticleVisibility.isReadableBy((String) null, AUTHOR_UUID, OTHER_UUID, false)).isFalse();
            assertThat(ArticleVisibility.isReadableBy("WHATEVER", AUTHOR_UUID, OTHER_UUID, false)).isFalse();
            assertThat(ArticleVisibility.isReadableBy("WHATEVER", AUTHOR_UUID, AUTHOR_UUID, false)).isTrue();
        }

        @Test
        @DisplayName("enum 版 → status 為 null 視為不公開")
        void enumVersion_nullStatus_treatedAsNonPublic() {
            assertThat(ArticleVisibility.isReadableBy((ArticleStatus) null, AUTHOR_UUID, OTHER_UUID, false)).isFalse();
        }

        @ParameterizedTest(name = "狀態 {0}")
        @EnumSource(ArticleStatus.class)
        @DisplayName("所有狀態 × (作者/他人/匿名) × (ADMIN 與否) → 完全符合政策矩陣")
        void allStatuses_matchPolicyMatrix(ArticleStatus status) {
            UUID[] viewerUuids = {AUTHOR_UUID, OTHER_UUID, null};
            for (int i = 0; i < viewerUuids.length; i++) {
                for (boolean admin : new boolean[]{false, true}) {
                    boolean isAuthor = viewerUuids[i] != null && viewerUuids[i].equals(AUTHOR_UUID);
                    boolean expected = status == ArticleStatus.PUBLISHED || admin || isAuthor;
                    boolean actual = ArticleVisibility.isReadableBy(status, AUTHOR_UUID, viewerUuids[i], admin);
                    assertThat(actual).as("status=%s viewer#%d admin=%s", status, i, admin).isEqualTo(expected);
                }
            }
        }
    }
}
