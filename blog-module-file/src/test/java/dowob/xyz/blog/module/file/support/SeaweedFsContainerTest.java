package dowob.xyz.blog.module.file.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SeaweedFsContainer 設定測試（不啟動容器，本機無 Docker 亦可執行）
 *
 * <p>只鎖定容器設定：映像檔版本、S3 port、帳密環境變數。容器能否實際啟動並提供 S3 服務，
 * 由使用它的 IT（{@code FileControllerIT}、{@code FileUploadQuotaIT}）在 CI 上驗證。
 * 映像檔版本另有一份相同的設定在 {@code blog-start} 的 E2E 支援類別中，兩處須同步。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SeaweedFsContainer 設定")
class SeaweedFsContainerTest {

    @Test
    @DisplayName("使用固定版本的 SeaweedFS 官方映像檔，不用浮動的 latest")
    void usesPinnedOfficialImage() {
        try (SeaweedFsContainer container = new SeaweedFsContainer()) {
            assertThat(container.getDockerImageName()).isEqualTo("chrislusf/seaweedfs:4.47");
        }
    }

    @Test
    @DisplayName("對外暴露 S3 port 8333")
    void exposesS3Port() {
        try (SeaweedFsContainer container = new SeaweedFsContainer()) {
            assertThat(container.getExposedPorts()).containsExactly(8333);
        }
    }

    @Test
    @DisplayName("以環境變數設定 S3 帳密（weed mini 據此啟用驗證），getter 回傳同一組值")
    void configuresCredentialsViaEnvironment() {
        try (SeaweedFsContainer container = new SeaweedFsContainer()) {
            assertThat(container.getEnvMap())
                    .containsEntry("AWS_ACCESS_KEY_ID", container.getAccessKey())
                    .containsEntry("AWS_SECRET_ACCESS_KEY", container.getSecretKey());
            assertThat(container.getAccessKey()).isNotBlank();
            assertThat(container.getSecretKey()).isNotBlank();
        }
    }
}
