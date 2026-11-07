package dowob.xyz.blog.module.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.Map;
import java.util.Set;

/**
 * 檔案模組外部化設定
 *
 * <p>
 * 將業務相關的設定（角色配額、允許的 MIME 類型）從程式碼搬移至
 * application.yaml，避免每次修改都需要重新編譯部署。
 * </p>
 *
 * <p>
 * 注意：單檔大小限制以程式常數 {@code FileServiceImpl.MAX_FILE_SIZE}（5MB）為準，並由
 * {@code spring.servlet.multipart.max-file-size}（application.yaml，同為 5MB）在請求進入 service 前先擋一道；
 * 兩者一致性由 blog-start 的 {@code MultipartYamlBindingTest} 守住，此處不重複設定。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ConfigurationProperties(prefix = "blog.file")
public record FileProperties(

        /**
         * 各角色的儲存配額上限
         * 鍵為角色名稱（大寫），值為 DataSize（支援 YAML 直接寫人類可讀格式）。
         * 例：USER: 10MB, AUTHOR: 500MB
         */
        Map<String, DataSize> quotas,

        /**
         * 允許上傳的 MIME 類型集合（使用 Set 確保無重複）
         */
        Set<String> allowedMimeTypes,

        /**
         * 圖片總像素（寬 × 高）上限，不限單邊；未設定時為 {@link #DEFAULT_MAX_IMAGE_PIXELS}（FILE-04，A6）
         */
        Long maxImagePixels

) {
    /** 圖片總像素預設上限：100MP（100,000,000 像素） */
    public static final long DEFAULT_MAX_IMAGE_PIXELS = 100_000_000L;

    /** 防禦性預設值：避免 YAML 未設定時的 NPE */
    public FileProperties {
        if (quotas == null) quotas = Map.of();
        if (allowedMimeTypes == null) allowedMimeTypes = Set.of();
        if (maxImagePixels == null) maxImagePixels = DEFAULT_MAX_IMAGE_PIXELS;
    }
}
