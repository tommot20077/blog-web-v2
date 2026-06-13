package dowob.xyz.blog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Dev profile secrets config")
class DevProfileSecretsConfigTest {

    @Test
    @DisplayName("base application yaml should not import dotenv file")
    void baseApplicationYamlShouldNotImportDotenvFile() throws IOException {
        String applicationYaml = new ClassPathResource("application.yaml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(applicationYaml)
                .doesNotContain("optional:file:.env[.properties]")
                .doesNotContain("optional:file:../.env[.properties]");
    }

    @Test
    @DisplayName("dev profile should import repo root dotenv file")
    void devProfileShouldImportRepoRootDotenvFile() throws IOException {
        String devYaml = new ClassPathResource("application-dev.yaml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(devYaml)
                .contains("optional:file:.env[.properties]")
                .contains("optional:file:../.env[.properties]");
    }

    @Test
    @DisplayName("dev profile should read local service settings from env without fallback")
    void devProfileShouldReadLocalServiceSettingsFromEnvWithoutFallback() throws IOException {
        String devYaml = new ClassPathResource("application-dev.yaml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(devYaml)
                .contains("${LOCAL_DB_URL}")
                .contains("${LOCAL_DB_USERNAME}")
                .contains("${LOCAL_DB_PASSWORD}")
                .contains("${LOCAL_REDIS_HOST}")
                .contains("${LOCAL_REDIS_PORT}")
                .contains("${LOCAL_REDIS_PASSWORD}")
                .contains("${LOCAL_MQ_HOST}")
                .contains("${LOCAL_MQ_PORT}")
                .contains("${LOCAL_MQ_USERNAME}")
                .contains("${LOCAL_MQ_PASSWORD}")
                .contains("${LOCAL_ES_URIS}")
                .contains("${LOCAL_ES_USERNAME}")
                .contains("${LOCAL_ES_PASSWORD}")
                .contains("${LOCAL_MINIO_ENDPOINT}")
                .contains("${LOCAL_MINIO_ACCESS_KEY}")
                .contains("${LOCAL_MINIO_SECRET_KEY}")
                .contains("${LOCAL_MINIO_BUCKET}");

        assertThat(devYaml)
                .doesNotContain("${LOCAL_DB_URL:")
                .doesNotContain("${LOCAL_DB_USERNAME:")
                .doesNotContain("${LOCAL_DB_PASSWORD:")
                .doesNotContain("${LOCAL_REDIS_HOST:")
                .doesNotContain("${LOCAL_REDIS_PORT:")
                .doesNotContain("${LOCAL_REDIS_PASSWORD:")
                .doesNotContain("${LOCAL_MQ_HOST:")
                .doesNotContain("${LOCAL_MQ_PORT:")
                .doesNotContain("${LOCAL_MQ_USERNAME:")
                .doesNotContain("${LOCAL_MQ_PASSWORD:")
                .doesNotContain("${LOCAL_ES_URIS:")
                .doesNotContain("${LOCAL_ES_USERNAME:")
                .doesNotContain("${LOCAL_ES_PASSWORD:")
                .doesNotContain("${LOCAL_MINIO_ENDPOINT:")
                .doesNotContain("${LOCAL_MINIO_ACCESS_KEY:")
                .doesNotContain("${LOCAL_MINIO_SECRET_KEY:")
                .doesNotContain("${LOCAL_MINIO_BUCKET:");
    }
}
