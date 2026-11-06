package dowob.xyz.blog.config;

import dowob.xyz.blog.infrastructure.config.MinioConfig;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正式 {@code application.yaml} 的 MinIO 逾時設定守衛（RACE-16／PERF-28）。
 *
 * <p>以真實 yaml 綁定 {@link MinioConfig}，斷言 connect／read／write 逾時存在且被套用到
 * 實際建出的 {@link OkHttpClient}（yaml 寫了卻沒被讀取，或被誤刪改回無限等待都會失敗）。不需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("application.yaml：MinIO 逾時設定")
class MinioTimeoutYamlBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("yaml 的 connect=5s、read=30s、write=30s 綁定到 MinioConfig 並套用到 OkHttpClient")
    void yamlMinioTimeouts_areBoundAndApplied() {
        runner.run(context -> {
            MinioConfig config = Binder.get(context.getEnvironment())
                    .bindOrCreate("minio", MinioConfig.class);

            assertThat(config.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(config.getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(config.getWriteTimeout()).isEqualTo(Duration.ofSeconds(30));

            OkHttpClient client = config.buildHttpClient();
            assertThat(client.connectTimeoutMillis()).isEqualTo(5_000);
            assertThat(client.readTimeoutMillis()).isEqualTo(30_000);
            assertThat(client.writeTimeoutMillis()).isEqualTo(30_000);
        });
    }
}
