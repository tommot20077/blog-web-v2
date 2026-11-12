package dowob.xyz.blog.module.series.model.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.dto.AuthorSummary;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
import dowob.xyz.blog.infrastructure.facade.dto.TagSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-26 / 決策 C6：系列詳情改用自己的 {@link SeriesArticleItem} 後，對外 JSON 形狀必須與改版前完全相同。
 *
 * <p>「改版前」的形狀以兩種方式固定：</p>
 * <ol>
 *   <li>{@link #RECORDED_ITEM_KEYS}／{@link #RECORDED_DETAIL_KEYS}：改版前（articles 為
 *       {@code ArticleSummaryResponse}）實際序列化出的 key 集合，逐字錄下；</li>
 *   <li>同一份資料分別序列化 {@code ArticleSummaryResponse}（改版前實際輸出的型別）與
 *       {@code SeriesArticleItem}，兩者的 JSON 必須逐值相等，含 null 欄位仍保留 key。</li>
 * </ol>
 * 序列化器以 {@link Jackson2ObjectMapperBuilder} 建立，與 Spring Boot 對外 API 使用的預設設定一致
 * （null 欄位照常輸出、日期為 ISO 字串）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SeriesArticleItem：對外 JSON 形狀與改版前完全相同（ARCH-26）")
class SeriesArticleItemJsonShapeTest {

    /** 改版前 {@code data.articles[]} 每一項的 key 集合（22 個）。 */
    private static final Set<String> RECORDED_ITEM_KEYS = new TreeSet<>(List.of(
            "uuid", "title", "summary", "coverImageUrl", "authorUuid", "authorNickname", "status",
            "viewCount", "createdAt", "updatedAt", "slug", "likeCount", "commentCount", "publishedAt",
            "tags", "rejectReason", "liked", "bookmarked", "lastReadProgress",
            "seriesUuid", "seriesTitle", "seriesPosition"));

    /** 改版前 {@code data} 的 key 集合（系列詳情本身）。 */
    private static final Set<String> RECORDED_DETAIL_KEYS = new TreeSet<>(List.of(
            "uuid", "title", "slug", "description", "coverImageUrl", "author", "articleCount",
            "articles", "myProgress", "createdAt", "updatedAt"));

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static ArticleSummaryResponse fullySetSummary() {
        UUID seriesUuid = UUID.randomUUID();
        return ArticleSummaryResponse.builder()
                .uuid(UUID.randomUUID())
                .title("標題")
                .summary("摘要")
                .coverImageUrl("https://img/c.png")
                .authorUuid(UUID.randomUUID())
                .authorNickname("Yuan")
                .status(ArticleStatus.PUBLISHED)
                .viewCount(12L)
                .createdAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5))
                .updatedAt(LocalDateTime.of(2026, 2, 3, 4, 5, 6))
                .slug("slug-1")
                .likeCount(3L)
                .commentCount(4)
                .publishedAt(LocalDateTime.of(2026, 1, 3, 3, 4, 5))
                .tags(List.of(TagSummaryResponse.builder().id(UUID.randomUUID()).name("Java").slug("java").build()))
                .rejectReason("原因")
                .liked(true)
                .bookmarked(false)
                .lastReadProgress(new BigDecimal("0.50"))
                .seriesUuid(seriesUuid)
                .seriesTitle("系列")
                .seriesPosition(2)
                .build();
    }

    @Test
    @DisplayName("前提：改版前的型別（ArticleSummaryResponse）序列化出的 key 集合就是錄下的 22 個")
    void recordedKeysMatchLegacyType() {
        JsonNode legacy = mapper.valueToTree(fullySetSummary());

        assertThat(keysOf(legacy)).isEqualTo(RECORDED_ITEM_KEYS);
    }

    @Test
    @DisplayName("SeriesArticleItem 的 key 集合與改版前完全相同")
    void itemKeysEqualRecordedKeys() {
        JsonNode json = mapper.valueToTree(SeriesArticleItem.from(fullySetSummary()));

        assertThat(keysOf(json)).isEqualTo(RECORDED_ITEM_KEYS);
    }

    @Test
    @DisplayName("同一份資料：SeriesArticleItem 與改版前型別的 JSON 逐值相等（全部欄位有值）")
    void itemJsonEqualsLegacyJson_fullySet() {
        ArticleSummaryResponse summary = fullySetSummary();

        JsonNode item = mapper.valueToTree(SeriesArticleItem.from(summary));
        JsonNode legacy = mapper.valueToTree(summary);

        assertThat(item).isEqualTo(legacy);
    }

    @Test
    @DisplayName("同一份資料：只有必填欄位時，null 欄位仍輸出 key，JSON 與改版前逐值相等")
    void itemJsonEqualsLegacyJson_nullsKept() {
        ArticleSummaryResponse summary = ArticleSummaryResponse.builder()
                .uuid(UUID.randomUUID()).title("A").build();

        JsonNode item = mapper.valueToTree(SeriesArticleItem.from(summary));

        assertThat(keysOf(item)).isEqualTo(RECORDED_ITEM_KEYS);
        assertThat(item.get("liked").isNull()).isTrue();
        assertThat(item.get("viewCount").asLong()).isZero();
        assertThat(item).isEqualTo(mapper.valueToTree(summary));
    }

    @Test
    @DisplayName("SeriesDetailResponse 的 key 集合不變，articles 的元素型別是 SeriesArticleItem")
    void detailShapeUnchanged() throws Exception {
        SeriesDetailResponse detail = new SeriesDetailResponse();
        detail.setUuid(UUID.randomUUID());
        detail.setAuthor(new AuthorSummary(UUID.randomUUID(), "n", null));
        detail.setArticles(List.of(SeriesArticleItem.from(fullySetSummary())));

        JsonNode json = mapper.valueToTree(detail);

        assertThat(keysOf(json)).isEqualTo(RECORDED_DETAIL_KEYS);
        ParameterizedType articlesType = (ParameterizedType) SeriesDetailResponse.class
                .getDeclaredField("articles").getGenericType();
        assertThat(articlesType.getActualTypeArguments()[0]).isEqualTo(SeriesArticleItem.class);
    }
}
