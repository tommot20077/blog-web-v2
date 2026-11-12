package dowob.xyz.blog.module.file.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * 整合測試用的 SeaweedFS S3 容器。
 *
 * <p><b>存在理由</b>：MinIO 社群版已停止維護，{@code minio/minio} 於 2026-09-11 自 Docker Hub 刪除、
 * quay.io 亦無法匿名拉取，CI 的檔案模組 IT 因此全數失敗。改以 SeaweedFS（Apache-2.0，持續維護）
 * 提供 S3 相容服務。應用程式碼仍使用 MinIO Java SDK——它是通用的 S3 client，且仍在維護，
 * 故只替換伺服器端，不動 SDK。</p>
 *
 * <p>官方映像檔預設執行 {@code weed mini -dir=/data}：單一容器內依序啟動 master → volume → filer
 * → S3 閘道，每一步都等前一個服務的健康檢查通過才繼續。因此 S3 port 的 {@code /healthz}
 * 回 200 時，其相依服務皆已就緒，可作為等待條件。以環境變數提供帳密會使 S3 閘道啟用驗證
 * （未提供時為「全部允許」模式），與正式環境的驗證行為一致。</p>
 *
 * <p><b>同步提醒</b>：{@code blog-start} 的 E2E 另有一份相同設定的
 * {@code dowob.xyz.blog.e2e.config.SeaweedFsContainer}（兩模組的測試類別無法共用，
 * 以 test-jar 共用會把本模組的測試用 {@code @SpringBootApplication} 帶進 E2E 的元件掃描）。
 * 升級映像檔版本時兩處須一併修改。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class SeaweedFsContainer extends GenericContainer<SeaweedFsContainer> {

    /** 固定版本的官方映像檔（不用 latest，避免上游變動讓 CI 無聲地改變行為） */
    private static final String IMAGE = "chrislusf/seaweedfs:4.47";

    /** S3 閘道 port */
    private static final int S3_PORT = 8333;

    /** 測試用 S3 access key */
    private static final String ACCESS_KEY = "seaweed-test-access";

    /** 測試用 S3 secret key */
    private static final String SECRET_KEY = "seaweed-test-secret";

    /**
     * 建立容器設定（尚未啟動）。
     */
    public SeaweedFsContainer() {
        super(DockerImageName.parse(IMAGE));
        withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY);
        withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY);
        withExposedPorts(S3_PORT);
        waitingFor(Wait.forHttp("/healthz")
                .forPort(S3_PORT)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    /**
     * 取 S3 端點 URL（容器啟動後才有對外 port）。
     *
     * @return 形如 {@code http://localhost:32768} 的端點
     */
    public String getS3Url() {
        return "http://" + getHost() + ":" + getMappedPort(S3_PORT);
    }

    /**
     * 取 S3 access key。
     *
     * @return access key
     */
    public String getAccessKey() {
        return ACCESS_KEY;
    }

    /**
     * 取 S3 secret key。
     *
     * @return secret key
     */
    public String getSecretKey() {
        return SECRET_KEY;
    }
}
