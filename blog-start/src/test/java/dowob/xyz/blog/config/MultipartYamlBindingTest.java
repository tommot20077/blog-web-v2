package dowob.xyz.blog.config;

import dowob.xyz.blog.module.file.service.FileServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正式 {@code application.yaml} 的 multipart 上限守衛（FILE-05）。
 *
 * <p>yaml 未設定時框架預設只有 1MB，與程式內 5MB 的單檔上限矛盾（5MB 以內的合法圖片會在進入 service 前被擋下）。
 * 本測試以真實 yaml 綁定 {@link MultipartProperties}，並與 {@link FileServiceImpl#MAX_FILE_SIZE} 對齊，
 * 兩處任一被改動而不一致都會在這裡失敗。不需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("application.yaml：multipart 上傳上限")
class MultipartYamlBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("Tomcat max-swallow-size 明確設為 10MB（大於 max-request-size、非 -1）")
    void yamlTomcatSwallowSize_isExplicitAndBounded() {
        runner.run(context -> {
            org.springframework.boot.autoconfigure.web.ServerProperties server = Binder.get(context.getEnvironment())
                    .bind("server", org.springframework.boot.autoconfigure.web.ServerProperties.class)
                    .orElseThrow(() -> new AssertionError("server.* 未設定"));
            MultipartProperties props = Binder.get(context.getEnvironment())
                    .bind("spring.servlet.multipart", MultipartProperties.class).get();

            assertThat(server.getTomcat().getMaxSwallowSize()).isEqualTo(DataSize.ofMegabytes(10));
            assertThat(server.getTomcat().getMaxSwallowSize().toBytes()).isGreaterThan(props.getMaxRequestSize().toBytes());
        });
    }

    @Test
    @DisplayName("max-file-size 與程式單檔上限一致；max-request-size 略大以容納 multipart overhead")
    void yamlMultipart_alignsWithServiceLimit() {
        runner.run(context -> {
            MultipartProperties props = Binder.get(context.getEnvironment())
                    .bind("spring.servlet.multipart", MultipartProperties.class)
                    .orElseThrow(() -> new AssertionError("spring.servlet.multipart 未設定"));

            assertThat(props.getMaxFileSize()).isEqualTo(DataSize.ofBytes(FileServiceImpl.MAX_FILE_SIZE));
            assertThat(props.getMaxRequestSize()).isGreaterThan(props.getMaxFileSize());
            assertThat(props.getMaxRequestSize().toBytes() - props.getMaxFileSize().toBytes())
                    .as("overhead 不宜過大（避免放行多檔大請求）")
                    .isLessThanOrEqualTo(DataSize.ofMegabytes(2).toBytes());
        });
    }
}
