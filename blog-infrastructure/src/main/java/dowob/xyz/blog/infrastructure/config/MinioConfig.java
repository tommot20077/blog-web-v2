package dowob.xyz.blog.infrastructure.config;

import io.minio.MinioClient;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * MinIO 物件儲存配置類
 *
 * <p>
 * 從 application.yaml 的 minio.* 前綴讀取配置：
 * </p>
 * 
 * <pre>
 * minio:
 *   endpoint: http://localhost:9000
 *   access-key: minioadmin
 *   secret-key: minioadmin
 *   bucket-name: blog-files
 * </pre>
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {

    /**
     * MinIO 服務端點 URL
     */
    @NotBlank(message = "MinIO endpoint 不可為空")
    private String endpoint;

    /**
     * MinIO 存取金鑰
     */
    @NotBlank(message = "MinIO access-key 不可為空")
    private String accessKey;

    /**
     * MinIO 密鑰
     */
    @NotBlank(message = "MinIO secret-key 不可為空")
    private String secretKey;

    /**
     * 預設儲存桶名稱
     */
    @NotBlank(message = "MinIO bucket-name 不可為空")
    private String bucketName;

    /**
     * 建立 MinIO 客戶端 Bean
     *
     * @return 配置好的 MinioClient 實例
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
