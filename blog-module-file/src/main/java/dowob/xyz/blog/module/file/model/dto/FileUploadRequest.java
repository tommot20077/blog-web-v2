package dowob.xyz.blog.module.file.model.dto;

import dowob.xyz.blog.module.file.model.UsageType;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.multipart.MultipartFile;

/**
 * 檔案上傳請求 DTO（OpenAPI 文件用）
 *
 * <p>
 * 僅供 springdoc 在 {@code POST /api/v1/files/upload} 端點上產生
 * {@code multipart/form-data} 的 requestBody schema。實際 controller
 * 仍使用 {@code @RequestParam} 個別綁定，因為 multipart 純文字 part 對
 * enum 的轉換走 {@code ConversionService}，比 {@code @RequestPart} 的
 * {@code HttpMessageConverter} 鏈更簡潔。
 * </p>
 *
 * @param file      上傳的二進位檔案
 * @param usageType 檔案用途分類
 * @author Yuan
 * @version 1.0
 */
@Schema(description = "Multipart 檔案上傳請求")
public record FileUploadRequest(
        @Schema(description = "要上傳的檔案（必填）", type = "string", format = "binary", requiredMode = Schema.RequiredMode.REQUIRED)
        MultipartFile file,

        @Schema(description = "檔案用途（必填）", requiredMode = Schema.RequiredMode.REQUIRED)
        UsageType usageType
) {
}
