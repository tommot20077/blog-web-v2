package dowob.xyz.blog.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MinioConfig 回歸護欄測試。
 *
 * <p>
 * <b>此測試存在的理由（請勿為了方便刪除或弱化）</b>：
 * 本專案的檔案存取控制設計（見
 * {@code docs/superpowers/specs/2026-07-26-file-access-control-design.md} §1、§3、§7）
 * 刻意把 MinIO bucket 維持**完全私有**，所有圖片一律經由後端代理端點
 * {@code GET /api/v1/files/{id}/content} 驗權後，302 轉導至短效 presigned URL 取得。
 * 這個設計的前提是「bucket 本身不可匿名讀取」——一旦 bucket 被設成
 * public-read（例如呼叫 {@code setBucketPolicy} 授予 {@code Principal: *}
 * 的 {@code s3:GetObject} 權限），後端所有的權限判斷（草稿只給作者/管理員看、
 * 已發布文章才公開等）就會被繞過，圖片網址一旦外流即永久可讀。
 * </p>
 *
 * <p>
 * 本機開發時，曾為了應急手動對執行中的容器下過
 * {@code mc anonymous set download}（僅影響當下容器，不在程式碼內），
 * 讓「圖片看不到」的症狀消失。<b>未來若又遇到類似症狀，正確的修法是檢查
 * 代理端點 {@code /api/v1/files/{id}/content} 的授權邏輯或 presigned URL
 * 產生流程，而不是回頭把 bucket policy 改成 public-read。</b>
 * 這支測試就是為了在有人「順手」這樣改時，能在 CI 就攔下來。
 * </p>
 *
 * <p>
 * <b>斷言策略</b>：採用「攔截 {@code setBucketPolicy} 參數、檢查 policy 內容」
 * 而非單純斷言「完全未呼叫 setBucketPolicy」。理由：後者過於武斷——
 * 若未來有正當理由需要呼叫 {@code setBucketPolicy}（例如設定僅限特定前綴、
 * 特定條件的限制性 policy），一律禁止呼叫會讓測試變成「為了通過而繞過」的
 * 阻礙。內容檢查則能精準鎖定「授予匿名讀取」這個危險組合本身
 * （{@code Effect=Allow} + {@code Principal=*}（或等價的 {@code AWS:*}）
 * + {@code Action} 含 {@code s3:GetObject}／{@code s3:*}／{@code *}），
 * 對其他形式的 policy 保持中立。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("MinioConfig 單元測試：bucket 私有性回歸護欄")
class MinioConfigTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 受測配置類別 */
    private MinioConfig config;

    /** 模擬的 MinIO 客戶端 */
    @Mock
    private MinioClient minioClient;

    private final ApplicationArguments noArgs = new DefaultApplicationArguments();

    @BeforeEach
    void setUp() {
        config = new MinioConfig();
        config.setEndpoint("http://localhost:9000");
        config.setAccessKey("minioadmin");
        config.setSecretKey("minioadmin");
        config.setBucketName("blog-files");
    }

    /**
     * bucket 不存在時：ensureBucket 應建立 bucket，
     * 且**不得**設定任何授予匿名讀取的 bucket policy。
     */
    @Test
    @DisplayName("ensureBucket：bucket 不存在時應建立 bucket，且不設定匿名可讀 policy")
    void ensureBucket_whenBucketNotExists_createsBucketWithoutAnonymousReadPolicy() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        ApplicationRunner runner = config.ensureBucket(minioClient);
        runner.run(noArgs);

        verify(minioClient).makeBucket(any(MakeBucketArgs.class));
        assertNoAnonymousReadPolicyEverGranted();
    }

    /**
     * bucket 已存在時：ensureBucket 不應重複建立，
     * 且**不得**設定任何授予匿名讀取的 bucket policy。
     */
    @Test
    @DisplayName("ensureBucket：bucket 已存在時不應重建，且不設定匿名可讀 policy")
    void ensureBucket_whenBucketExists_doesNotSetAnonymousReadPolicy() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        ApplicationRunner runner = config.ensureBucket(minioClient);
        runner.run(noArgs);

        verify(minioClient, org.mockito.Mockito.never()).makeBucket(any(MakeBucketArgs.class));
        assertNoAnonymousReadPolicyEverGranted();
    }

    /**
     * 攔截所有對 {@code setBucketPolicy} 的呼叫（若有），解析其 policy JSON，
     * 斷言其中沒有任何 statement 同時符合
     * {@code Effect=Allow} + {@code Principal=*}（或等價全體匿名） +
     * {@code Action} 含 {@code s3:GetObject}（或等價的萬用讀取）。
     *
     * <p>
     * 目前 {@code ensureBucket()} 實作完全不呼叫 {@code setBucketPolicy}，
     * 所以 captor 會捕捉到 0 筆呼叫，此時斷言自然通過（allSatisfy 對空集合恆真）。
     * 若未來有人加入 {@code setBucketPolicy} 呼叫並帶入公開放行的 policy，
     * 這裡會捕捉到該次呼叫並解析出違規內容而失敗。
     * </p>
     */
    private void assertNoAnonymousReadPolicyEverGranted() throws Exception {
        ArgumentCaptor<SetBucketPolicyArgs> policyCaptor = ArgumentCaptor.forClass(SetBucketPolicyArgs.class);
        verify(minioClient, atLeast(0)).setBucketPolicy(policyCaptor.capture());

        List<SetBucketPolicyArgs> capturedPolicies = policyCaptor.getAllValues();
        for (SetBucketPolicyArgs args : capturedPolicies) {
            assertThat(grantsAnonymousRead(args.config()))
                    .as("bucket policy 不得授予匿名讀取（Principal=* + s3:GetObject）："
                            + "偵測到的 policy 內容為 %s", args.config())
                    .isFalse();
        }
    }

    /**
     * 判斷一段 bucket policy JSON 是否含有「授予匿名讀取」的 statement：
     * {@code Effect=Allow} 且 {@code Principal} 為萬用（{@code "*"} 或
     * {@code {"AWS":"*"}} / {@code {"AWS":["*"]}}）且 {@code Action}
     * 含 {@code s3:GetObject}（或涵蓋它的萬用寫法 {@code s3:*} / {@code *}）。
     */
    private static boolean grantsAnonymousRead(String policyJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(policyJson);
            JsonNode statements = root.path("Statement");
            if (!statements.isArray()) {
                return false;
            }
            for (JsonNode statement : statements) {
                boolean allow = "Allow".equalsIgnoreCase(statement.path("Effect").asText());
                boolean anonymousPrincipal = isWildcardPrincipal(statement.path("Principal"));
                boolean getObjectAction = containsGetObjectAction(statement.path("Action"));
                if (allow && anonymousPrincipal && getObjectAction) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw new IllegalArgumentException("無法解析 bucket policy JSON：" + policyJson, e);
        }
    }

    private static boolean isWildcardPrincipal(JsonNode principal) {
        if (principal.isTextual()) {
            return "*".equals(principal.asText());
        }
        if (principal.isObject()) {
            JsonNode aws = principal.path("AWS");
            if (aws.isTextual()) {
                return "*".equals(aws.asText());
            }
            if (aws.isArray()) {
                for (JsonNode item : aws) {
                    if ("*".equals(item.asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsGetObjectAction(JsonNode action) {
        if (action.isTextual()) {
            return matchesGetObject(action.asText());
        }
        if (action.isArray()) {
            for (JsonNode item : action) {
                if (matchesGetObject(item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesGetObject(String action) {
        return "s3:GetObject".equals(action) || "s3:*".equals(action) || "*".equals(action);
    }
}
