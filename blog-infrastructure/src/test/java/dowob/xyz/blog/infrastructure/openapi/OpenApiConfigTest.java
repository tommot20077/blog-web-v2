package dowob.xyz.blog.infrastructure.openapi;

import dowob.xyz.blog.infrastructure.config.OpenApiConfig;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OpenApiConfig} 全域 customizer 單元測試。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("OpenApiConfig 全域 customizer")
class OpenApiConfigTest {

    @Test
    @DisplayName("每個 operation 補 500（B0001），已手寫的 500 不覆蓋")
    void internalErrorCustomizer_adds500WithoutOverwriting() {
        Operation plain = new Operation();
        Operation handwritten = new Operation().responses(
                new ApiResponses().addApiResponse("500", new ApiResponse().description("手寫")));
        OpenAPI openApi = new OpenAPI().paths(new Paths()
                .addPathItem("/a", new PathItem().get(plain))
                .addPathItem("/b", new PathItem().post(handwritten)));

        new OpenApiConfig().internalErrorOpenApiCustomizer().customise(openApi);

        assertThat(plain.getResponses().get("500").getDescription()).contains("B0001");
        assertThat(handwritten.getResponses().get("500").getDescription()).isEqualTo("手寫");
    }
}
