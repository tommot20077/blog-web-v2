package dowob.xyz.blog.module.file.model.dto;

import dowob.xyz.blog.module.file.model.UsageType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 檔案上傳回應 DTO
 *
 * <p>
 * 上傳成功後回傳給前端的檔案資訊。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadResponse {

    /**
     * 檔案 UUID
     */
    private UUID id;

    /**
     * 檔案存取 URL
     */
    private String url;

    /**
     * 圖片寬度（像素）
     */
    private Integer width;

    /**
     * 圖片高度（像素）
     */
    private Integer height;

    /**
     * 檔案大小（位元組）
     */
    private Long size;

    /**
     * 檔案用途類型
     */
    private UsageType usageType;
}
