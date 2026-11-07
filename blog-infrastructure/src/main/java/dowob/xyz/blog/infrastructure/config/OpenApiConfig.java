package dowob.xyz.blog.infrastructure.config;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.openapi.CommonResponsesOperationCustomizer;
import dowob.xyz.blog.infrastructure.openapi.ResponseBodyOperationCustomizer;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI (Swagger) 基礎配置類
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class OpenApiConfig {

    /**
     * API 版本號
     */
    private static final String API_VERSION = "1.0";

    /**
     * 認證方案名稱
     */
    private static final String API_SECURITY_SCHEME_NAME = "Bearer Authentication";

    static {
        // 登入主體（@AuthenticationPrincipal AuthUser）由 Spring Security 注入，不是請求參數；
        // 不忽略的話 default-flat-param-object 會把 uuid／role 展開成每個登入端點的 query 參數
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthUser.class);
    }


    /**
     * 自定義 OpenAPI 配置
     *
     * @return OpenAPI 對象
     */
    @Bean
    public OpenAPI customOpenApi() {
        Info info = new Info();
        info.setTitle("Blog API");
        info.setVersion(API_VERSION);
        info.setDescription("部落格 API 接口文檔 - 基於 Spring Boot 3 & Modular Monolith 架構");

        License license = new License();
        license.setName("Apache 2.0");
        license.setUrl("https://www.apache.org/licenses/LICENSE-2.0");
        info.setLicense(license);

        SecurityRequirement securityRequirement = new SecurityRequirement();
        securityRequirement.addList(API_SECURITY_SCHEME_NAME);

        Components components = new Components();
        SecurityScheme securityScheme = new SecurityScheme();
        securityScheme.setType(SecurityScheme.Type.HTTP);
        securityScheme.setScheme("bearer");
        securityScheme.setBearerFormat("JWT");

        components.addSecuritySchemes(API_SECURITY_SCHEME_NAME, securityScheme);
        components.addSchemas(ResponseBodyOperationCustomizer.ERROR_SCHEMA_NAME, errorResponseSchema());

        return new OpenAPI().info(info).addSecurityItem(securityRequirement).components(components);
    }

    /**
     * 錯誤回應 schema：對應專案 {@code ApiResponse.failed(...)} 的實際 JSON（{@code data} 恆為 null）。
     *
     * @return 錯誤回應 schema
     */
    private static Schema<?> errorResponseSchema() {
        Schema<?> schema = new Schema<>().type("object")
                .description("錯誤回應：HTTP 狀態碼非 2xx 時的 body，data 恆為 null（reindex 的 409 為例外，見該端點說明）");
        schema.addProperty("code", new StringSchema().description("錯誤碼，例如 A0001"));
        schema.addProperty("message", new StringSchema().description("錯誤訊息"));
        Schema<?> data = new Schema<>().description("恆為 null");
        data.addType("null");
        schema.addProperty("data", data);
        schema.addProperty("timestamp", new IntegerSchema().format("int64").description("伺服器時間戳（毫秒）"));
        schema.setRequired(java.util.List.of("code", "message", "data", "timestamp"));
        return schema;
    }

    /**
     * 成功／錯誤回應 body customizer：補齊遺失的成功回應，並讓手寫錯誤回應改引用 {@code ApiErrorResponse}
     *
     * @return operation customizer
     */
    @Bean
    public OperationCustomizer responseBodyOperationCustomizer() {
        return new ResponseBodyOperationCustomizer();
    }

    /**
     * 共通錯誤回應 customizer：依 {@code @PreAuthorize}／{@code @RateLimited} 補 401／403／429
     *
     * @return operation customizer
     */
    @Bean
    public OperationCustomizer commonResponsesOperationCustomizer() {
        return new CommonResponsesOperationCustomizer();
    }

    /**
     * 全域 customizer：每個 operation 補上 500（{@code B0001}）。
     *
     * <p>500 對所有端點都可能發生，不逐一標註；已手寫 500 的 operation 不覆蓋。</p>
     *
     * <p>同時為所有尚無 content 的 4xx／5xx（含 customizer 補的 401／403／429）補上 {@code ApiErrorResponse}，
     * 讓錯誤回應一律引用同一個 schema；handler 明確指定 content 的回應保持原樣。此處在全部 operation customizer
     * 之後執行，與它們的順序無關。</p>
     *
     * @return OpenAPI customizer
     */
    @Bean
    public OpenApiCustomizer internalErrorOpenApiCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(pathItem -> pathItem.readOperations().forEach(operation -> {
                if (operation.getResponses() == null) {
                    operation.setResponses(new ApiResponses());
                }
                if (operation.getResponses().get("500") == null) {
                    operation.getResponses().addApiResponse("500", new ApiResponse()
                            .description(CommonErrorCode.SYSTEM_EXECUTION_ERROR.getCode() + " 系統執行出錯"));
                }
                operation.getResponses().forEach((status, response) -> {
                    if ((status.startsWith("4") || status.startsWith("5")) && response.getContent() == null) {
                        response.setContent(ResponseBodyOperationCustomizer.errorContent());
                    }
                });
            }));
        };
    }
}
