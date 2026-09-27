package dowob.xyz.blog.module.article.model.dto.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ArticleListQuery 單元測試
 *
 * <p>驗證文章列表篩選參數的正規化（去重、夾界、容錯）在 compact constructor 生效，
 * 以及 Spring MVC 的 constructor binding 確實會觸發該 constructor，
 * 且舊的單值 {@code categorySlug} 呼叫方式仍被接受（向下相容）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleListQuery 單元測試")
class ArticleListQueryTest {

    @Nested
    @DisplayName("compact constructor 正規化")
    class Normalization {

        @Test
        @DisplayName("全部未提供時正規化為「不篩選、依最新發布排序」")
        void allAbsent_normalizesToUnfilteredLatest() {
            ArticleListQuery query = new ArticleListQuery(null, null, null, null, null);

            assertThat(query.tags()).isEmpty();
            assertThat(query.categorySlug()).isEmpty();
            assertThat(query.authorUuids()).isEmpty();
            assertThat(query.publishedWithinDays()).isNull();
            assertThat(query.sortOrder()).isEqualTo(ArticleListSort.LATEST);
            assertThat(query).isEqualTo(ArticleListQuery.unfiltered());
        }

        @Test
        @DisplayName("tags 去空白、轉小寫、去除空值與重複，並保留首次出現的順序")
        void tags_normalizedTrimmedLowercasedAndDeduplicated() {
            ArticleListQuery query = new ArticleListQuery(
                    Arrays.asList(" Java ", "spring", "JAVA", "", null, "  "), null, null, null, null);

            assertThat(query.tags()).containsExactly("java", "spring");
        }

        @Test
        @DisplayName("tags 超過上限時不截斷——截斷會讓結果不符請求，上限改由 PublishedArticleCriteria 拒絕（400）")
        void tags_beyondMax_keptInFullForCriteriaToReject() {
            List<String> many = IntStream.range(0, ArticleListQuery.MAX_VALUES_PER_FILTER + 5)
                    .mapToObj(i -> "tag-" + i).toList();

            ArticleListQuery query = new ArticleListQuery(many, null, null, null, null);

            assertThat(query.tags()).containsExactlyElementsOf(many);
        }

        @Test
        @DisplayName("categorySlug 與 tags 採相同正規化（V23 起分類 slug 由 DB CHECK 保證全小寫，轉小寫不會錯過任何分類）")
        void categorySlug_normalizedLikeTags() {
            ArticleListQuery query = new ArticleListQuery(
                    null, Arrays.asList("Tech", " tech", "life", null, " Tech "), null, null, null);

            assertThat(query.categorySlug()).containsExactly("tech", "life");
        }

        @Test
        @DisplayName("authorUuids 去除 null 與重複，超過上限時不截斷（OR 截斷會靜默少回結果）")
        void authorUuids_deduplicatedWithoutTruncation() {
            UUID first = UUID.randomUUID();
            List<UUID> input = new ArrayList<>(Arrays.asList(first, null, first));
            IntStream.range(0, ArticleListQuery.MAX_VALUES_PER_FILTER + 5).forEach(i -> input.add(UUID.randomUUID()));

            ArticleListQuery query = new ArticleListQuery(null, null, input, null, null);

            assertThat(query.authorUuids()).hasSize(ArticleListQuery.MAX_VALUES_PER_FILTER + 6);
            assertThat(query.authorUuids().get(0)).isEqualTo(first);
            assertThat(query.authorUuids()).doesNotHaveDuplicates().doesNotContainNull();
        }

        @Test
        @DisplayName("publishedWithinDays 為 0 或負數時視為不篩選日期")
        void publishedWithinDays_nonPositive_meansNoDateFilter() {
            assertThat(new ArticleListQuery(null, null, null, 0, null).publishedWithinDays()).isNull();
            assertThat(new ArticleListQuery(null, null, null, -30, null).publishedWithinDays()).isNull();
        }

        @Test
        @DisplayName("publishedWithinDays 超過上限時夾界（避免算出 PostgreSQL timestamp 範圍外的日期而 500）")
        void publishedWithinDays_aboveMax_clampedToMax() {
            ArticleListQuery query = new ArticleListQuery(null, null, null, Integer.MAX_VALUE, null);

            assertThat(query.publishedWithinDays()).isEqualTo(ArticleListQuery.MAX_PUBLISHED_WITHIN_DAYS);
        }

        @Test
        @DisplayName("publishedWithinDays 合法值原封不動")
        void publishedWithinDays_legal_keptAsIs() {
            assertThat(new ArticleListQuery(null, null, null, 30, null).publishedWithinDays()).isEqualTo(30);
        }

        @Test
        @DisplayName("sort 不分大小寫")
        void sort_caseInsensitive() {
            assertThat(new ArticleListQuery(null, null, null, null, "POPULAR").sortOrder())
                    .isEqualTo(ArticleListSort.POPULAR);
            assertThat(new ArticleListQuery(null, null, null, null, " commented ").sortOrder())
                    .isEqualTo(ArticleListSort.COMMENTED);
        }

        @Test
        @DisplayName("sort 為未知值時退回 latest，不因拼錯而 500（與 PageQuery 正規化而非拒絕的取向一致）")
        void sort_unknown_fallsBackToLatest() {
            ArticleListQuery query = new ArticleListQuery(null, null, null, null, "popularr");

            assertThat(query.sortOrder()).isEqualTo(ArticleListSort.LATEST);
            assertThat(query.sort()).isEqualTo("latest");
        }

        @Test
        @DisplayName("正規化後的清單不可變，下游無法繞過夾界")
        void normalizedLists_areImmutable() {
            ArticleListQuery query = new ArticleListQuery(List.of("java"), List.of("tech"), null, null, null);

            assertThatThrownBy(() -> query.tags().add("x")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> query.categorySlug().add("x")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> query.authorUuids().add(UUID.randomUUID()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Spring MVC constructor binding")
    class ConstructorBinding {

        /** 僅供綁定驗證用的最小 controller */
        @RestController
        static class EchoController {

            /**
             * 回顯綁定後的篩選參數。
             *
             * @param query 由 Spring 依 query string 建構的篩選參數
             * @return 「tags|categorySlug|作者數|days|sort」字串
             */
            @GetMapping("/echo-list")
            public String echo(ArticleListQuery query) {
                return String.join(",", query.tags())
                        + "|" + String.join(",", query.categorySlug())
                        + "|" + query.authorUuids().size()
                        + "|" + query.publishedWithinDays()
                        + "|" + query.sort();
            }
        }

        private final MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new EchoController())
                .build();

        @Test
        @DisplayName("未帶任何參數時，controller 收到「不篩選、latest」")
        void noParams_controllerReceivesUnfilteredLatest() throws Exception {
            mockMvc.perform(get("/echo-list"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("||0|null|latest"));
        }

        @Test
        @DisplayName("逗號分隔的 tags 被拆成清單並經正規化")
        void commaSeparatedTags_splitIntoNormalizedList() throws Exception {
            mockMvc.perform(get("/echo-list").param("tags", "Java,spring,java"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("java,spring||0|null|latest"));
        }

        @Test
        @DisplayName("重複參數形式（tags=a&tags=b）同樣被收集")
        void repeatedTagParams_collected() throws Exception {
            mockMvc.perform(get("/echo-list").param("tags", "java").param("tags", "spring"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("java,spring||0|null|latest"));
        }

        @Test
        @DisplayName("舊的單值 categorySlug 呼叫方式仍被接受（向下相容），並轉為小寫")
        void legacySingleCategorySlug_stillAcceptedAndLowercased() throws Exception {
            mockMvc.perform(get("/echo-list").param("categorySlug", "Backend"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("|backend|0|null|latest"));
        }

        @Test
        @DisplayName("authorUuids、publishedWithinDays、sort 一併綁定")
        void allFilters_bound() throws Exception {
            mockMvc.perform(get("/echo-list")
                            .param("authorUuids", UUID.randomUUID() + "," + UUID.randomUUID())
                            .param("publishedWithinDays", "30")
                            .param("sort", "popular"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("||2|30|popular"));
        }

        @Test
        @DisplayName("authorUuids 格式錯誤時回 400，而非靜默忽略（型別錯誤不屬於可正規化的範圍）")
        void malformedAuthorUuid_returns400() throws Exception {
            mockMvc.perform(get("/echo-list").param("authorUuids", "not-a-uuid"))
                    .andExpect(status().isBadRequest());
        }
    }
}
