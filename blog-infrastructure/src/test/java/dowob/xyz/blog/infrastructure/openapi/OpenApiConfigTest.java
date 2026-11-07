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

    @Test
    @DisplayName("500 與其他沒有 content 的 4xx／5xx 引用 ApiErrorResponse；已有 content 的不覆蓋")
    void errorCustomizer_fillsErrorSchemaOnResponsesWithoutContent() {
        io.swagger.v3.oas.models.media.Content explicit = new io.swagger.v3.oas.models.media.Content().addMediaType("*/*",
                new io.swagger.v3.oas.models.media.MediaType().schema(
                        new io.swagger.v3.oas.models.media.Schema<>().$ref("#/components/schemas/Custom")));
        Operation op = new Operation().responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("OK"))
                .addApiResponse("401", new ApiResponse().description("A0005"))
                .addApiResponse("409", new ApiResponse().description("衝突").content(explicit)));
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem("/a", new PathItem().get(op)));

        new OpenApiConfig().internalErrorOpenApiCustomizer().customise(openApi);

        String errorRef = "#/components/schemas/ApiErrorResponse";
        assertThat(op.getResponses().get("500").getContent().get("*/*").getSchema().get$ref()).isEqualTo(errorRef);
        assertThat(op.getResponses().get("401").getContent().get("*/*").getSchema().get$ref()).isEqualTo(errorRef);
        assertThat(op.getResponses().get("409").getContent().get("*/*").getSchema().get$ref())
                .isEqualTo("#/components/schemas/Custom");
        assertThat(op.getResponses().get("200").getContent()).isNull();
    }

    @Test
    @DisplayName("components 註冊 ApiErrorResponse：code／message／data（恆 null）／timestamp")
    void customOpenApi_registersErrorSchema() {
        io.swagger.v3.oas.models.media.Schema<?> schema =
                new OpenApiConfig().customOpenApi().getComponents().getSchemas().get("ApiErrorResponse");

        assertThat(schema).isNotNull();
        assertThat(schema.getProperties()).containsOnlyKeys("code", "message", "data", "timestamp");
        assertThat(schema.getProperties().get("data").getTypes()).containsExactly("null");
    }
}
