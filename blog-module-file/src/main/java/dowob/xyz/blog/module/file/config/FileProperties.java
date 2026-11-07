package dowob.xyz.blog.module.file.config;

import dowob.xyz.blog.module.file.model.UsageType;
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
 * 注意：單檔大小上限（A6b）依用途分兩種——文章圖片 {@link #maxArticleImageSize}（預設 20MB）、
 * 頭像 {@link #maxAvatarSize}（預設 5MB），由 {@code FileServiceImpl} 在 service 層強制。
 * {@code spring.servlet.multipart.max-file-size}（application.yaml）須等於其中較大者（文章圖片），
 * 在請求進入 service 前先擋一道；兩者一致性由 blog-start 的 {@code MultipartYamlBindingTest} 守住。
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
        Long maxImagePixels,

        /**
         * 文章圖片（封面、內文）單檔大小上限；未設定時為 {@link #DEFAULT_MAX_ARTICLE_IMAGE_SIZE}（A6b）
         */
        DataSize maxArticleImageSize,

        /**
         * 頭像單檔大小上限；未設定時為 {@link #DEFAULT_MAX_AVATAR_SIZE}（A6b）
         */
        DataSize maxAvatarSize

) {
    /** 圖片總像素預設上限：100MP（100,000,000 像素） */
    public static final long DEFAULT_MAX_IMAGE_PIXELS = 100_000_000L;

    /** 文章圖片單檔預設上限：20MB */
    public static final DataSize DEFAULT_MAX_ARTICLE_IMAGE_SIZE = DataSize.ofMegabytes(20);

    /** 頭像單檔預設上限：5MB */
    public static final DataSize DEFAULT_MAX_AVATAR_SIZE = DataSize.ofMegabytes(5);

    /** 防禦性預設值：避免 YAML 未設定時的 NPE */
    public FileProperties {
        if (quotas == null) quotas = Map.of();
        if (allowedMimeTypes == null) allowedMimeTypes = Set.of();
        if (maxImagePixels == null) maxImagePixels = DEFAULT_MAX_IMAGE_PIXELS;
        if (maxArticleImageSize == null) maxArticleImageSize = DEFAULT_MAX_ARTICLE_IMAGE_SIZE;
        if (maxAvatarSize == null) maxAvatarSize = DEFAULT_MAX_AVATAR_SIZE;
    }

    /**
     * 依用途取得單檔大小上限（位元組）：頭像用 {@link #maxAvatarSize}，其餘（文章封面、內文圖片）用 {@link #maxArticleImageSize}。
     *
     * @param usageType 檔案用途
     * @return 單檔上限（位元組）
     */
    public long maxFileSizeBytes(UsageType usageType) {
        return (usageType == UsageType.AVATAR ? maxAvatarSize : maxArticleImageSize).toBytes();
    }
}
