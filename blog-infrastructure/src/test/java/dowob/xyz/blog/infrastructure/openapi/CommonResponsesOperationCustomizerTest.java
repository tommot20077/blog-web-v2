package dowob.xyz.blog.infrastructure.openapi;

import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommonResponsesOperationCustomizer} 單元測試：共通錯誤回應由標註推導，不逐一手寫。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("OpenAPI 共通錯誤回應 customizer")
class CommonResponsesOperationCustomizerTest {

    private final CommonResponsesOperationCustomizer customizer = new CommonResponsesOperationCustomizer();

    /** 測試用 controller：涵蓋公開、登入、權限、限流、類別層限流與既有手寫回應 */
    static class Fixture {
        public void publicRead() {
        }

        @PreAuthorize("isAuthenticated()")
        public void authenticatedOnly() {
        }

        @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
        public void needsAuthority() {
        }

        @RateLimited("search")
        public void rateLimited() {
        }

        @PreAuthorize("hasAnyRole('ADMIN')")
        public void needsRole() {
        }

        @PreAuthorize("isAuthenticated()")
        public void handwritten401() {
        }
    }

    @RateLimited("class-level")
    static class ClassLevelFixture {
        public void anything() {
        }
    }

    private Operation apply(Class<?> type, String method) throws Exception {
        Operation op = new Operation();
        return customizer.customize(op, new HandlerMethod(type.getDeclaredConstructor().newInstance(), type.getMethod(method)));
    }

    @Test
    @DisplayName("公開端點：不加 401／403／429")
    void publicEndpoint_addsNothing() throws Exception {
        Operation op = apply(Fixture.class, "publicRead");
        assertThat(op.getResponses()).isNull();
    }

    @Test
    @DisplayName("isAuthenticated()：只加 401（A0005）")
    void authenticated_adds401Only() throws Exception {
        Operation op = apply(Fixture.class, "authenticatedOnly");
        assertThat(op.getResponses()).containsOnlyKeys("401");
        assertThat(op.getResponses().get("401").getDescription()).contains("A0005");
    }

    @Test
    @DisplayName("hasAuthority／hasRole：加 401 與 403（A0006）")
    void authority_adds401And403() throws Exception {
        assertThat(apply(Fixture.class, "needsAuthority").getResponses()).containsOnlyKeys("401", "403");
        Operation role = apply(Fixture.class, "needsRole");
        assertThat(role.getResponses()).containsOnlyKeys("401", "403");
        assertThat(role.getResponses().get("403").getDescription()).contains("A0006");
    }

    @Test
    @DisplayName("@RateLimited（方法層）：加 429（A0009）並說明 Retry-After header")
    void rateLimited_adds429WithRetryAfter() throws Exception {
        Operation op = apply(Fixture.class, "rateLimited");
        ApiResponse r = op.getResponses().get("429");
        assertThat(r).isNotNull();
        assertThat(r.getDescription()).contains("A0009");
        assertThat(r.getHeaders()).containsKey("Retry-After");
    }

    @Test
    @DisplayName("@RateLimited（類別層）也加 429")
    void classLevelRateLimited_adds429() throws Exception {
        assertThat(apply(ClassLevelFixture.class, "anything").getResponses()).containsKey("429");
    }

    @Test
    @DisplayName("已手寫的回應不被覆蓋")
    void existingResponse_isNotOverwritten() throws Exception {
        Operation op = new Operation();
        op.setResponses(new io.swagger.v3.oas.models.responses.ApiResponses()
                .addApiResponse("401", new ApiResponse().description("手寫")));
        customizer.customize(op, new HandlerMethod(new Fixture(), Fixture.class.getMethod("handwritten401")));
        assertThat(op.getResponses().get("401").getDescription()).isEqualTo("手寫");
    }
}
