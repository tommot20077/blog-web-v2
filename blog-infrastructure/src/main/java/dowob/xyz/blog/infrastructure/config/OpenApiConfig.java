package dowob.xyz.blog.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
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
}
