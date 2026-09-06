package dowob.xyz.blog.common.api.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PageQuery 單元測試
 *
 * <p>
 * 驗證分頁參數的正規化（夾界）在 compact constructor 生效，
 * 以及 Spring MVC 的 constructor binding 確實會觸發該 constructor——
 * 後者是本設計「不可能漏」的前提，屬推論不可取代的實測項。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("PageQuery 單元測試")
class PageQueryTest {

    @Nested
    @DisplayName("compact constructor 正規化")
    class Normalization {

        @Test
        @DisplayName("size 超過上限時夾成上限")
        void whenSizeExceedsMax_clampsToMax() {
            assertThat(new PageQuery(1, 99999).size()).isEqualTo(PageQuery.MAX_SIZE);
        }

        @Test
        @DisplayName("size 剛好等於上限時原值保留，不被多夾一次")
        void whenSizeExactlyMax_keepsValue() {
            assertThat(new PageQuery(1, PageQuery.MAX_SIZE).size()).isEqualTo(PageQuery.MAX_SIZE);
        }

        @Test
        @DisplayName("size 未提供時保持 null，預設值不由本型別決定")
        void whenSizeAbsent_staysNull() {
            assertThat(new PageQuery(1, null).size()).isNull();
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
        @DisplayName("端點傳入超過上限的預設值時同樣被夾界，不得繞過上限")
        void sizeOrDefaultClampsOversizedFallback() {
            assertThat(new PageQuery(1, null).sizeOrDefault(99999)).isEqualTo(PageQuery.MAX_SIZE);
        }

        @Test
        @DisplayName("size 為 0 或負數時夾成 1，不與「未提供」混淆")
        void whenSizeNotPositive_clampsToOne() {
            assertThat(new PageQuery(1, 0).size()).isEqualTo(1);
            assertThat(new PageQuery(1, -5).size()).isEqualTo(1);
        }

        @Test
        @DisplayName("page 未提供或小於 1 時夾成 1")
        void whenPageAbsentOrBelowOne_clampsToOne() {
            assertThat(new PageQuery(null, 10).page()).isEqualTo(1);
            assertThat(new PageQuery(0, 10).page()).isEqualTo(1);
            assertThat(new PageQuery(-3, 10).page()).isEqualTo(1);
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
    @DisplayName("Spring MVC constructor binding")
    class ConstructorBinding {

        /** 僅供綁定驗證用的最小 controller */
        @RestController
        static class EchoController {

            /**
             * 回顯綁定後的分頁參數。
             *
             * @param pageQuery 由 Spring 依 query string 建構的分頁參數
             * @return "page:size" 字串
             */
            @GetMapping("/echo-page")
            public String echo(PageQuery pageQuery) {
                return pageQuery.page() + ":" + pageQuery.sizeOrDefault(20);
            }
        }

        private final MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new EchoController())
                .build();

        @Test
        @DisplayName("query string 超限時，controller 收到的已是夾界後的值")
        void whenQueryStringExceedsMax_controllerReceivesClampedValue() throws Exception {
            mockMvc.perform(get("/echo-page").param("page", "-5").param("size", "99999"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("1:" + PageQuery.MAX_SIZE));
        }

        @Test
        @DisplayName("query string 未帶參數時，採端點自訂預設值而非 0")
        void whenQueryStringAbsent_controllerReceivesEndpointDefault() throws Exception {
            mockMvc.perform(get("/echo-page"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("1:20"));
        }

        @Test
        @DisplayName("query string 合法時原值傳入")
        void whenQueryStringLegal_controllerReceivesSameValue() throws Exception {
            mockMvc.perform(get("/echo-page").param("page", "3").param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("3:20"));
        }
    }
}
