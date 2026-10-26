package dowob.xyz.blog.module.file.event;

import java.util.UUID;

/**
 * 圖片上傳事件
 *
 * <p>
 * 當圖片上傳至 MinIO 後發布此事件，觸發縮圖產生流程。
 * </p>
 *
 * @param eventId     事件 dedup key（發送端每次 publish 時隨機產生；舊格式 in-flight 訊息為 null）
 * @param fileId      已上傳檔案的 UUID
 * @param storagePath MinIO 儲存路徑
 * @param contentType 檔案 MIME 類型
 * @author Yuan
 * @version 1.0
 */
public record ImageUploadedEvent(UUID eventId, UUID fileId, String storagePath, String contentType) {
}
