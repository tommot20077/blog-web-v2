package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ArticleContentFreezePolicy 單元測試
 *
 * <p>
 * 內容凍結是「哪些狀態下文章內容可被改寫」的唯一真相，PUT 更新與版本還原兩條寫入路徑共用。
 * 本測試以 {@link EnumSource} 覆蓋全部 {@link ArticleStatus}，新增狀態時若未在政策裡表態，
 * 這裡會立刻紅（比照 {@code ArticleCommandSubServiceTest} 的狀態轉換矩陣測試）。
 * </p>
 *
 * <pre>
 * # | 狀態             | 可編輯 | 理由
 * 1 | DRAFT           | ✅    | 草稿本來就是編輯中
 * 2 | REJECTED        | ✅    | 被駁回後要能改一改重新送審
 * 3 | PENDING_REVIEW  | ❌    | 審核 TOCTOU：admin 審的是 A、通過的不能是 B
 * 4 | PUBLISHED       | ❌    | 已公開內容不可就地改寫（須先 ARCHIVED → DRAFT）
 * 5 | ARCHIVED        | ❌    | 已封存內容不可就地改寫
 * 6 | null            | ❌    | 防禦性：狀態不明一律視為凍結（fail-safe）
 * </pre>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleContentFreezePolicy 內容凍結政策")
class ArticleContentFreezePolicyTest {

    @ParameterizedTest
    @EnumSource(value = ArticleStatus.class, names = {"DRAFT", "REJECTED"})
    @DisplayName("DRAFT / REJECTED：內容可編輯，assert 不拋例外")
    void editableStatuses_areContentEditable(ArticleStatus status) {
        assertThat(ArticleContentFreezePolicy.isContentEditable(status)).isTrue();
        assertThatCode(() -> ArticleContentFreezePolicy.assertContentEditable(status))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = ArticleStatus.class, names = {"PENDING_REVIEW", "PUBLISHED", "ARCHIVED"})
    @DisplayName("PENDING_REVIEW / PUBLISHED / ARCHIVED：內容凍結，assert 拋 ARTICLE_EDIT_NOT_ALLOWED（A0209）")
    void frozenStatuses_throwEditNotAllowed(ArticleStatus status) {
        assertThat(ArticleContentFreezePolicy.isContentEditable(status)).isFalse();
        assertThatThrownBy(() -> ArticleContentFreezePolicy.assertContentEditable(status))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo(ArticleErrorCode.ARTICLE_EDIT_NOT_ALLOWED.getCode());
    }

    @Test
    @DisplayName("狀態為 null：fail-safe 視為凍結（維持 PUT 守衛既有行為）")
    void nullStatus_isTreatedAsFrozen() {
        assertThat(ArticleContentFreezePolicy.isContentEditable(null)).isFalse();
        assertThatThrownBy(() -> ArticleContentFreezePolicy.assertContentEditable(null))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo(ArticleErrorCode.ARTICLE_EDIT_NOT_ALLOWED.getCode());
    }

    @ParameterizedTest
    @EnumSource(ArticleStatus.class)
    @DisplayName("完整性：每個 ArticleStatus 都必須被政策明確表態（新增狀態時強制回來補）")
    void everyStatusIsClassified(ArticleStatus status) {
        boolean editable = ArticleContentFreezePolicy.isContentEditable(status);
        boolean expected = status == ArticleStatus.DRAFT || status == ArticleStatus.REJECTED;
        assertThat(editable)
                .as("狀態 %s 的內容凍結判定與政策表不符", status)
                .isEqualTo(expected);
    }
}
