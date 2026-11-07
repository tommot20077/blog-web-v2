package dowob.xyz.blog.config;

import dowob.xyz.blog.module.file.config.FileProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正式 {@code application.yaml} 的 multipart 上限守衛（FILE-05、A6b）。
 *
 * <p>yaml 未設定時框架預設只有 1MB，與 service 的單檔上限矛盾（合法圖片會在進入 service 前被擋下）。
 * 本測試以真實 yaml 綁定 {@link MultipartProperties} 與 {@link FileProperties}：multipart 的 max-file-size
 * 必須等於文章圖片單檔上限（20MB，頭像 5MB 較小，由 service 層擋）；request 略大以容納 overhead；
 * Tomcat swallow 大於 request。任一被改動而不一致都會在這裡失敗。不需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("application.yaml：multipart 上傳上限")
class MultipartYamlBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("Tomcat max-swallow-size 明確設為 32MB（大於 max-request-size、非 -1）")
    void yamlTomcatSwallowSize_isExplicitAndBounded() {
        runner.run(context -> {
            org.springframework.boot.autoconfigure.web.ServerProperties server = Binder.get(context.getEnvironment())
                    .bind("server", org.springframework.boot.autoconfigure.web.ServerProperties.class)
                    .orElseThrow(() -> new AssertionError("server.* 未設定"));
            MultipartProperties props = Binder.get(context.getEnvironment())
                    .bind("spring.servlet.multipart", MultipartProperties.class).get();

            assertThat(server.getTomcat().getMaxSwallowSize()).isEqualTo(DataSize.ofMegabytes(32));
            assertThat(server.getTomcat().getMaxSwallowSize().toBytes()).isGreaterThan(props.getMaxRequestSize().toBytes());
        });
    }

    @Test
    @DisplayName("max-file-size 等於文章圖片單檔上限 20MB；頭像 5MB；max-request-size 略大以容納 multipart overhead")
    void yamlMultipart_alignsWithServiceLimit() {
        runner.run(context -> {
            MultipartProperties props = Binder.get(context.getEnvironment())
                    .bind("spring.servlet.multipart", MultipartProperties.class)
                    .orElseThrow(() -> new AssertionError("spring.servlet.multipart 未設定"));

            FileProperties file = Binder.get(context.getEnvironment())
                    .bind("blog.file", FileProperties.class)
                    .orElseThrow(() -> new AssertionError("blog.file 未設定"));

            assertThat(context.getEnvironment().getProperty("blog.file.max-article-image-size")).isEqualTo("20MB");
            assertThat(context.getEnvironment().getProperty("blog.file.max-avatar-size")).isEqualTo("5MB");
            assertThat(file.maxArticleImageSize()).isEqualTo(DataSize.ofMegabytes(20));
            assertThat(file.maxAvatarSize()).isEqualTo(DataSize.ofMegabytes(5));
            assertThat(props.getMaxFileSize()).isEqualTo(file.maxArticleImageSize());
            assertThat(props.getMaxRequestSize()).isGreaterThan(props.getMaxFileSize());
            assertThat(props.getMaxRequestSize().toBytes() - props.getMaxFileSize().toBytes())
                    .as("overhead 不宜過大（避免放行多檔大請求）")
                    .isLessThanOrEqualTo(DataSize.ofMegabytes(2).toBytes());
        });
    }

    @Test
    @DisplayName("blog.file.max-image-pixels 在 yaml 明確設為 100MP（FILE-04，A6；不限單邊）")
    void yamlMaxImagePixels_isExplicit100Megapixels() {
        runner.run(context -> {
            assertThat(context.getEnvironment().getProperty("blog.file.max-image-pixels"))
                    .as("yaml 必須明確設定，不能只靠程式預設值")
                    .isEqualTo("100000000");
            dowob.xyz.blog.module.file.config.FileProperties props = Binder.get(context.getEnvironment())
                    .bind("blog.file", dowob.xyz.blog.module.file.config.FileProperties.class).get();
            assertThat(props.maxImagePixels()).isEqualTo(100_000_000L);
        });
    }

    @Test
    @DisplayName("配額：AUTHOR 2GB、USER 10MB（A6b）")
    void yamlQuotas_authorTwoGigabytes_userTenMegabytes() {
        runner.run(context -> {
            FileProperties file = Binder.get(context.getEnvironment())
                    .bind("blog.file", FileProperties.class)
                    .orElseThrow(() -> new AssertionError("blog.file 未設定"));

            assertThat(file.quotas().get("AUTHOR")).isEqualTo(DataSize.ofGigabytes(2));
            assertThat(file.quotas().get("USER")).isEqualTo(DataSize.ofMegabytes(10));
        });
    }

    @Test
    @DisplayName("blog.file.binding-reconcile 在 yaml 明確設定：近 3 天、保留 7 天（B1／D6）")
    void yamlBindingReconcile_isExplicit() {
        runner.run(context -> {
            assertThat(context.getEnvironment().getProperty("blog.file.binding-reconcile.recent-article-days")).isEqualTo("3");
            assertThat(context.getEnvironment().getProperty("blog.file.binding-reconcile.unbound-file-retention-days")).isEqualTo("7");
            FileProperties file = Binder.get(context.getEnvironment())
                    .bind("blog.file", FileProperties.class)
                    .orElseThrow(() -> new AssertionError("blog.file 未設定"));

            assertThat(file.bindingReconcile().recentArticleDays()).isEqualTo(3);
            assertThat(file.bindingReconcile().unboundFileRetentionDays()).isEqualTo(7);
        });
    }
}
