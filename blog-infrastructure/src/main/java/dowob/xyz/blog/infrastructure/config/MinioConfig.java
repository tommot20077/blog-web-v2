package dowob.xyz.blog.infrastructure.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import io.minio.http.HttpUtils;
import okhttp3.OkHttpClient;

import java.time.Duration;

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
 *   connect-timeout: 5s   # 選填，預設 5s
 *   read-timeout: 30s     # 選填，預設 30s
 *   write-timeout: 30s    # 選填，預設 30s
 * </pre>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
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
     * 連線逾時（預設 5 秒）：物件儲存不可達時快速失敗，不讓呼叫端執行緒（與其持有的資源）無限期卡住
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * 讀取逾時（預設 30 秒）：兩次資料到達之間的最長等待，對串流下載是「閒置」逾時而非整體逾時
     */
    private Duration readTimeout = Duration.ofSeconds(30);

    /**
     * 寫入逾時（預設 30 秒）：上傳（單檔上限 5MB）過程中兩次寫出之間的最長等待
     */
    private Duration writeTimeout = Duration.ofSeconds(30);

    /**
     * 建立套用 connect／read／write 逾時的 OkHttp 客戶端（RACE-16／PERF-28）。
     *
     * <p>直接沿用 SDK 自己建預設 client 的 {@link HttpUtils#newDefaultHttpClient}（固定 HTTP/1.1，
     * 並保留 SDK 對外部憑證環境變數的支援），只替換三個逾時；數值來自 {@code minio.connect-timeout} 等屬性，
     * 預設 5s／30s／30s。不自行 {@code new OkHttpClient}，避免與 SDK 預設行為（例如 HTTP/2 協商）分歧。</p>
     *
     * @return 套用逾時設定的 OkHttpClient
     */
    public OkHttpClient buildHttpClient() {
        return HttpUtils.newDefaultHttpClient(
                connectTimeout.toMillis(), writeTimeout.toMillis(), readTimeout.toMillis());
    }

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
                .httpClient(buildHttpClient())
                .build();
    }

    /**
     * 確保 bucket 存在，不存在則自動建立。
     * 避免因 bucket 缺失導致所有上傳請求回傳 500。
     */
    @Bean
    public org.springframework.boot.ApplicationRunner ensureBucket(MinioClient minioClient) {
        return args -> {
            try {
                boolean exists = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(bucketName).build());
                if (!exists) {
                    minioClient.makeBucket(
                            MakeBucketArgs.builder().bucket(bucketName).build());
                    log.info("MinIO bucket '{}' 已建立", bucketName);
                } else {
                    log.debug("MinIO bucket '{}' 已存在", bucketName);
                }
            } catch (Exception e) {
                log.error("MinIO bucket 初始化失敗（bucket={}）：{}", bucketName, e.getMessage());
            }
        };
    }
}
