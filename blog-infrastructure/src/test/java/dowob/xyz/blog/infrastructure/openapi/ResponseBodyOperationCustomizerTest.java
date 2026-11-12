package dowob.xyz.blog.infrastructure.openapi;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResponseBodyOperationCustomizer} 單元測試：成功回應補齊、錯誤回應統一引用 {@code ApiErrorResponse}。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("OpenAPI 成功／錯誤回應 body customizer")
class ResponseBodyOperationCustomizerTest {

    private static final String SUCCESS_REF = "#/components/schemas/ApiResponseFoo";
    private static final String ERROR_REF = "#/components/schemas/ApiErrorResponse";

    private final ResponseBodyOperationCustomizer customizer = new ResponseBodyOperationCustomizer();

    /** 測試用 handler */
    static class Fixture {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001")
        public String handwrittenError() {
            return null;
        }

        @ResponseStatus(HttpStatus.CREATED)
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001")
        public String created() {
            return null;
        }

        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001")
        public void noContentVoid() {
        }

        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "衝突",
                content = @io.swagger.v3.oas.annotations.media.Content(
                        schema = @Schema(ref = "#/components/schemas/ApiResponseFoo")))
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001")
        public String explicitContent() {
            return null;
        }

        public String plain() {
            return null;
        }
    }

    private static Content contentOf(String ref) {
        return new Content().addMediaType("*/*", new MediaType().schema(new io.swagger.v3.oas.models.media.Schema<>().$ref(ref)));
    }

    private static String refOf(ApiResponse response) {
        return response.getContent().get("*/*").getSchema().get$ref();
    }

    private Operation apply(Operation op, String method) throws Exception {
        return customizer.customize(op, new HandlerMethod(new Fixture(), Fixture.class.getMethod(method)));
    }

    private static Operation withErrorOnly(String status) {
        return new Operation().responses(new ApiResponses()
                .addApiResponse(status, new ApiResponse().description("A0001").content(contentOf(SUCCESS_REF))));
    }

    @Test
    @DisplayName("只有手寫錯誤回應：補 200「成功」並沿用 springdoc 推導的成功 schema，錯誤回應改引用 ApiErrorResponse")
    void handwrittenErrorOnly_addsSuccessAndReplacesErrorSchema() throws Exception {
        Operation op = apply(withErrorOnly("400"), "handwrittenError");

        assertThat(op.getResponses().get("200").getDescription()).isEqualTo("成功");
        assertThat(refOf(op.getResponses().get("200"))).isEqualTo(SUCCESS_REF);
        assertThat(refOf(op.getResponses().get("400"))).isEqualTo(ERROR_REF);
        assertThat(op.getResponses().get("400").getDescription()).isEqualTo("A0001");
    }

    @Test
    @DisplayName("@ResponseStatus(CREATED)：補 201 而非 200")
    void responseStatusCreated_addsCreated() throws Exception {
        Operation op = apply(withErrorOnly("400"), "created");

        assertThat(op.getResponses()).containsKey("201").doesNotContainKey("200");
        assertThat(refOf(op.getResponses().get("201"))).isEqualTo(SUCCESS_REF);
    }

    @Test
    @DisplayName("回傳 void：補成功回應但不帶 content")
    void voidReturn_successWithoutContent() throws Exception {
        Operation op = apply(withErrorOnly("400"), "noContentVoid");

        assertThat(op.getResponses().get("200")).isNotNull();
        assertThat(op.getResponses().get("200").getContent()).isNull();
    }

    @Test
    @DisplayName("已有 2xx：不重複補、不動成功 schema")
    void existingSuccess_isKept() throws Exception {
        Operation op = new Operation().responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("OK").content(contentOf(SUCCESS_REF))));

        apply(op, "plain");

        assertThat(op.getResponses()).containsOnlyKeys("200");
        assertThat(op.getResponses().get("200").getDescription()).isEqualTo("OK");
    }

    @Test
    @DisplayName("只有 304 視為已有成功回應，不補 200")
    void onlyNotModified_noSuccessAdded() throws Exception {
        Operation op = new Operation().responses(new ApiResponses()
                .addApiResponse("304", new ApiResponse().description("未變更")));

        apply(op, "plain");

        assertThat(op.getResponses()).containsOnlyKeys("304");
    }

    @Test
    @DisplayName("handler 手寫明確 content 的錯誤回應不覆蓋")
    void explicitErrorContent_isNotOverwritten() throws Exception {
        Operation op = new Operation().responses(new ApiResponses()
                .addApiResponse("409", new ApiResponse().description("衝突").content(contentOf(SUCCESS_REF)))
                .addApiResponse("400", new ApiResponse().description("A0001").content(contentOf(SUCCESS_REF))));

        apply(op, "explicitContent");

        assertThat(refOf(op.getResponses().get("409"))).isEqualTo(SUCCESS_REF);
        assertThat(refOf(op.getResponses().get("400"))).isEqualTo(ERROR_REF);
        assertThat(refOf(op.getResponses().get("200"))).isEqualTo(SUCCESS_REF);
    }

    @Test
    @DisplayName("沒有 responses 的 operation：補成功回應，不拋例外")
    void noResponses_addsSuccess() throws Exception {
        Operation op = apply(new Operation(), "plain");

        assertThat(op.getResponses()).containsOnlyKeys("200");
    }

    @Test
    @DisplayName("多個手寫錯誤回應：全部改引用 ApiErrorResponse，成功 schema 取第一個；5xx 同樣處理")
    void multipleErrors_allReplacedIncluding5xx() throws Exception {
        Operation op = new Operation().responses(new ApiResponses()
                .addApiResponse("400", new ApiResponse().description("a").content(contentOf(SUCCESS_REF)))
                .addApiResponse("404", new ApiResponse().description("b").content(contentOf(SUCCESS_REF)))
                .addApiResponse("503", new ApiResponse().description("c").content(contentOf(SUCCESS_REF))));

        apply(op, "plain");

        assertThat(refOf(op.getResponses().get("400"))).isEqualTo(ERROR_REF);
        assertThat(refOf(op.getResponses().get("404"))).isEqualTo(ERROR_REF);
        assertThat(refOf(op.getResponses().get("503"))).isEqualTo(ERROR_REF);
        assertThat(refOf(op.getResponses().get("200"))).isEqualTo(SUCCESS_REF);
    }

    @Test
    @DisplayName("沒有 content 的錯誤回應（如 customizer 補的 401）：本 customizer 不處理，成功回應不帶 content")
    void errorWithoutContent_isLeftAlone() throws Exception {
        Operation op = new Operation().responses(new ApiResponses()
                .addApiResponse("401", new ApiResponse().description("A0005")));

        apply(op, "plain");

        assertThat(op.getResponses().get("401").getContent()).isNull();
        assertThat(op.getResponses().get("200").getContent()).isNull();
    }

    @Test
    @DisplayName("非 $ref 的 schema（inline）原樣複製到成功回應；沒有 schema 的 media type 也不拋例外")
    void inlineAndMissingSchema_areCopied() throws Exception {
        Content content = new Content()
                .addMediaType("application/json", new MediaType().schema(new io.swagger.v3.oas.models.media.StringSchema()))
                .addMediaType("text/plain", new MediaType());
        Operation op = new Operation().responses(new ApiResponses()
                .addApiResponse("400", new ApiResponse().description("a").content(content)));

        apply(op, "plain");

        Content success = op.getResponses().get("200").getContent();
        assertThat(success.get("application/json").getSchema().getType()).isEqualTo("string");
        assertThat(success.get("text/plain").getSchema()).isNull();
    }
}
