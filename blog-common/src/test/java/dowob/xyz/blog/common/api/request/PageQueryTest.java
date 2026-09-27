package dowob.xyz.blog.common.api.request;

import dowob.xyz.blog.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PageQuery 單元測試
 *
 * <p>
 * 驗證分頁參數超出合法範圍時一律拒絕（而非靜默修正），以及
 * {@link PageQueryArgumentResolver} 確實在 controller 取得參數前完成解析與驗證，
 * 並經 {@link GlobalExceptionHandler} 回 400——後者是「超出範圍回 400」這個契約
 * 在 HTTP 層成立的前提，屬推論不可取代的實測項。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("PageQuery 單元測試")
class PageQueryTest {

    @Nested
    @DisplayName("compact constructor 驗證")
    class Validation {

        @Test
        @DisplayName("size 超過上限時拒絕，不再靜默夾成上限")
        void whenSizeExceedsMax_rejected() {
            assertThatThrownBy(() -> new PageQuery(1, PageQuery.MAX_SIZE + 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("size 剛好等於上限時接受")
        void whenSizeExactlyMax_accepted() {
            assertThat(new PageQuery(1, PageQuery.MAX_SIZE).size()).isEqualTo(PageQuery.MAX_SIZE);
        }

        @Test
        @DisplayName("size 為 0 或負數時拒絕")
        void whenSizeNotPositive_rejected() {
            assertThatThrownBy(() -> new PageQuery(1, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PageQuery(1, -5)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("page 為 0 或負數時拒絕")
        void whenPageBelowOne_rejected() {
            assertThatThrownBy(() -> new PageQuery(0, 10)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PageQuery(-3, 10)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("page 未提供時為 1；size 未提供時保持 null（預設值由端點決定）")
        void whenAbsent_pageDefaultsToOneAndSizeStaysNull() {
            PageQuery query = new PageQuery(null, null);

            assertThat(query.page()).isEqualTo(1);
            assertThat(query.size()).isNull();
        }

        @Test
        @DisplayName("sizeOrDefault 在 size 未提供時採端點傳入的預設值")
        void sizeOrDefaultUsesEndpointFallbackWhenAbsent() {
            assertThat(new PageQuery(1, null).sizeOrDefault(20)).isEqualTo(20);
        }

        @Test
        @DisplayName("sizeOrDefault 在 size 已提供時忽略預設值")
        void sizeOrDefaultIgnoresFallbackWhenPresent() {
            assertThat(new PageQuery(1, 5).sizeOrDefault(20)).isEqualTo(5);
        }

        @Test
        @DisplayName("端點傳入超過上限的預設值時被夾界，不得繞過上限（預設值由程式碼決定，不是 client 輸入）")
        void sizeOrDefaultClampsOversizedFallback() {
            assertThat(new PageQuery(1, null).sizeOrDefault(99999)).isEqualTo(PageQuery.MAX_SIZE);
        }

        @Test
        @DisplayName("合法值原封不動通過")
        void whenValuesLegal_passesThrough() {
            PageQuery query = new PageQuery(3, 20);

            assertThat(query.page()).isEqualTo(3);
            assertThat(query.size()).isEqualTo(20);
        }

        @Test
        @DisplayName("上限值為 1000，與 ArticleList 前端現行傳值對齊")
        void maxSizeIsOneThousand() {
            assertThat(PageQuery.MAX_SIZE).isEqualTo(1000);
        }
    }

    @Nested
    @DisplayName("PageQueryArgumentResolver（HTTP 層）")
    class ResolverBinding {

        /** 僅供綁定驗證用的最小 controller */
        @RestController
        static class EchoController {

            /**
             * 回顯解析後的分頁參數。
             *
             * @param pageQuery 由 resolver 依 query string 建構的分頁參數
             * @return "page:size" 字串
             */
            @GetMapping("/echo-page")
            public String echo(PageQuery pageQuery) {
                return pageQuery.page() + ":" + pageQuery.sizeOrDefault(20);
            }
        }

        private final MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new EchoController())
                .setCustomArgumentResolvers(new PageQueryArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        @Test
        @DisplayName("size 超過上限 → 400（A0001），不再靜默夾界")
        void whenSizeExceedsMax_returns400() throws Exception {
            mockMvc.perform(get("/echo-page").param("size", String.valueOf(PageQuery.MAX_SIZE + 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("A0001"));
        }

        @Test
        @DisplayName("page=0 → 400")
        void whenPageIsZero_returns400() throws Exception {
            mockMvc.perform(get("/echo-page").param("page", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("A0001"));
        }

        @Test
        @DisplayName("size=0 → 400")
        void whenSizeIsZero_returns400() throws Exception {
            mockMvc.perform(get("/echo-page").param("size", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("A0001"));
        }

        @Test
        @DisplayName("非整數 → 400")
        void whenNotInteger_returns400() throws Exception {
            mockMvc.perform(get("/echo-page").param("page", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("A0001"));
        }

        @Test
        @DisplayName("query string 未帶參數或為空字串時，採端點預設值")
        void whenAbsentOrBlank_controllerReceivesEndpointDefault() throws Exception {
            mockMvc.perform(get("/echo-page"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("1:20"));
            mockMvc.perform(get("/echo-page").param("page", "").param("size", " "))
                    .andExpect(status().isOk())
                    .andExpect(content().string("1:20"));
        }

        @Test
        @DisplayName("query string 合法時原值傳入")
        void whenQueryStringLegal_controllerReceivesSameValue() throws Exception {
            mockMvc.perform(get("/echo-page").param("page", "3").param("size", String.valueOf(PageQuery.MAX_SIZE)))
                    .andExpect(status().isOk())
                    .andExpect(content().string("3:" + PageQuery.MAX_SIZE));
        }
    }
}
