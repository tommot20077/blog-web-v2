package dowob.xyz.blog.module.article.model;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.model.dto.request.ArticleListQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PublishedArticleCriteria 單元測試
 *
 * <p>驗證 API 層的篩選參數轉成 mapper 查詢條件時的換算：日期區間以呼叫端給定的「現在」計算
 * （不在此取系統時鐘，測試因而可決定性驗證），以及 AND 語意所需的標籤數與去重後一致。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("PublishedArticleCriteria 單元測試")
class PublishedArticleCriteriaTest {

    /** 固定的「現在」 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 12, 0);

    @Test
    @DisplayName("不篩選的查詢 → 無任何條件、依 latest 排序")
    void of_unfilteredQuery_hasNoConditions() {
        PublishedArticleCriteria criteria = PublishedArticleCriteria.of(ArticleListQuery.unfiltered(), NOW);

        assertThat(criteria.getTagSlugs()).isEmpty();
        assertThat(criteria.getTagCount()).isZero();
        assertThat(criteria.getCategorySlugs()).isEmpty();
        assertThat(criteria.getAuthorUuids()).isEmpty();
        assertThat(criteria.getPublishedAfter()).isNull();
        assertThat(criteria.getSortKey()).isEqualTo("latest");
    }

    @Test
    @DisplayName("publishedWithinDays → publishedAfter 為「現在」往前推 N 天")
    void of_withPublishedWithinDays_publishedAfterIsNowMinusDays() {
        ArticleListQuery query = new ArticleListQuery(null, null, null, 30, null);

        PublishedArticleCriteria criteria = PublishedArticleCriteria.of(query, NOW);

        assertThat(criteria.getPublishedAfter()).isEqualTo(NOW.minusDays(30));
    }

    @Test
    @DisplayName("tagCount 等於去重後的標籤數（HAVING COUNT = tagCount 的 AND 語意依賴此值）")
    void of_tagCount_equalsDistinctTagCount() {
        ArticleListQuery query = new ArticleListQuery(List.of("java", "JAVA", "spring"), null, null, null, null);

        PublishedArticleCriteria criteria = PublishedArticleCriteria.of(query, NOW);

        assertThat(criteria.getTagSlugs()).containsExactly("java", "spring");
        assertThat(criteria.getTagCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("tags 超過上限 → 拒絕（A0210），不得截斷後照查")
    void of_tagsBeyondLimit_rejected() {
        ArticleListQuery query = new ArticleListQuery(slugs(ArticleListQuery.MAX_VALUES_PER_FILTER + 1), null, null, null, null);

        assertThatThrownBy(() -> PublishedArticleCriteria.of(query, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ArticleErrorCode.ARTICLE_LIST_FILTER_TOO_MANY_VALUES);
    }

    @Test
    @DisplayName("categorySlug 超過上限 → 拒絕（OR 截斷會靜默少回結果）")
    void of_categoriesBeyondLimit_rejected() {
        ArticleListQuery query = new ArticleListQuery(null, slugs(ArticleListQuery.MAX_VALUES_PER_FILTER + 1), null, null, null);

        assertThatThrownBy(() -> PublishedArticleCriteria.of(query, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ArticleErrorCode.ARTICLE_LIST_FILTER_TOO_MANY_VALUES);
    }

    @Test
    @DisplayName("authorUuids 超過上限 → 拒絕（OR 截斷會靜默少回結果）")
    void of_authorsBeyondLimit_rejected() {
        List<UUID> authors = IntStream.range(0, ArticleListQuery.MAX_VALUES_PER_FILTER + 1)
                .mapToObj(i -> UUID.randomUUID()).toList();
        ArticleListQuery query = new ArticleListQuery(null, null, authors, null, null);

        assertThatThrownBy(() -> PublishedArticleCriteria.of(query, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ArticleErrorCode.ARTICLE_LIST_FILTER_TOO_MANY_VALUES);
    }

    @Test
    @DisplayName("三個清單都剛好等於上限 → 接受")
    void of_allFiltersExactlyAtLimit_accepted() {
        List<UUID> authors = IntStream.range(0, ArticleListQuery.MAX_VALUES_PER_FILTER)
                .mapToObj(i -> UUID.randomUUID()).toList();
        ArticleListQuery query = new ArticleListQuery(
                slugs(ArticleListQuery.MAX_VALUES_PER_FILTER), slugs(ArticleListQuery.MAX_VALUES_PER_FILTER),
                authors, null, null);

        PublishedArticleCriteria criteria = PublishedArticleCriteria.of(query, NOW);

        assertThat(criteria.getTagCount()).isEqualTo(ArticleListQuery.MAX_VALUES_PER_FILTER);
        assertThat(criteria.getCategorySlugs()).hasSize(ArticleListQuery.MAX_VALUES_PER_FILTER);
        assertThat(criteria.getAuthorUuids()).hasSize(ArticleListQuery.MAX_VALUES_PER_FILTER);
    }

    @Test
    @DisplayName("分類、作者與排序原樣帶入")
    void of_categoryAuthorAndSort_passedThrough() {
        UUID author = UUID.randomUUID();
        ArticleListQuery query = new ArticleListQuery(null, List.of("tech"), List.of(author), null, "commented");

        PublishedArticleCriteria criteria = PublishedArticleCriteria.of(query, NOW);

        assertThat(criteria.getCategorySlugs()).containsExactly("tech");
        assertThat(criteria.getAuthorUuids()).containsExactly(author);
        assertThat(criteria.getSortKey()).isEqualTo("commented");
    }

    /**
     * 產生 n 個相異的 slug。
     *
     * @param n 數量
     * @return slug 清單
     */
    private static List<String> slugs(int n) {
        return IntStream.range(0, n).mapToObj(i -> "slug-" + i).toList();
    }
}
