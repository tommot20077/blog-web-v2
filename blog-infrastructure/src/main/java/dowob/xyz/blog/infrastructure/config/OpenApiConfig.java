package dowob.xyz.blog.infrastructure.config;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.infrastructure.openapi.CommonResponsesOperationCustomizer;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
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

        return new OpenAPI().info(info).addSecurityItem(securityRequirement).components(components);
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
            }));
        };
    }
}
