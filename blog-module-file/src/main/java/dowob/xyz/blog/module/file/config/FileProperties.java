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
 * 注意：單檔大小限制（MAX_FILE_SIZE）由 Spring Boot
 * {@code spring.servlet.multipart.max-file-size} 管控，此處不重複設定。
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
        Set<String> allowedMimeTypes

) {
    /** 防禦性預設值：避免 YAML 未設定時的 NPE */
    public FileProperties {
        if (quotas == null) quotas = Map.of();
        if (allowedMimeTypes == null) allowedMimeTypes = Set.of();
    }
}
