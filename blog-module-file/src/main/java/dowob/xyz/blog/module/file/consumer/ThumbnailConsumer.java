package dowob.xyz.blog.module.file.consumer;

import dowob.xyz.blog.module.file.event.ImageUploadedEvent;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 縮圖產生消費者
 *
 * <p>
 * 監聽 file.thumbnail Queue，收到圖片上傳事件後從 MinIO 下載原始圖片，
 * 使用 Thumbnailator 產生 300px 寬縮圖後上傳回 MinIO，並更新 DB 的 has_thumbnail 欄位。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailConsumer {

    /**
     * MinIO 客戶端
     */
    private final MinioClient minioClient;

    /**
     * 檔案元資料 Repository
     */
    private final FileMetadataRepository fileMetadataRepository;

    /**
     * MinIO 儲存桶名稱
     */
    @Value("${minio.bucket-name}")
    private String bucketName;

    /**
     * 處理圖片上傳事件，產生縮圖
     *
     * @param event 圖片上傳事件
     */
    @RabbitListener(queues = "file.thumbnail", containerFactory = "autoAckContainerFactory")
    public void handleImageUploaded(ImageUploadedEvent event) {
        if (!event.contentType().startsWith("image/")) {
            log.debug("非圖片類型，跳過縮圖處理: {}", event.contentType());
            return;
        }

        try {
            String ext = extractExtension(event.storagePath());
            String thumbPath = buildThumbPath(event.storagePath());

            try (InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(event.storagePath())
                            .build())) {

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                Thumbnails.of(inputStream)
                        .width(300)
                        .keepAspectRatio(true)
                        .outputFormat(ext)
                        .toOutputStream(outputStream);

                byte[] thumbBytes = outputStream.toByteArray();
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(thumbPath)
                                .stream(new ByteArrayInputStream(thumbBytes), thumbBytes.length, -1)
                                .contentType(event.contentType())
                                .build());
            }

            fileMetadataRepository.findById(event.fileId()).ifPresent(metadata -> {
                metadata.setHasThumbnail(true);
                fileMetadataRepository.save(metadata);
            });

            log.info("縮圖產生成功: {}", thumbPath);
        } catch (Exception e) {
            log.error("縮圖產生失敗，fileId={}: {}", event.fileId(), e.getMessage(), e);
        }
    }

    /**
     * 從路徑提取副檔名
     *
     * @param path 檔案路徑
     * @return 副檔名（不含點），若無則返回 "jpg"
     */
    private String extractExtension(String path) {
        if (path == null || !path.contains(".")) {
            return "jpg";
        }
        return path.substring(path.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 根據原始路徑建構縮圖路徑
     *
     * @param storagePath 原始儲存路徑
     * @return 縮圖路徑
     */
    private String buildThumbPath(String storagePath) {
        int dotIndex = storagePath.lastIndexOf('.');
        if (dotIndex < 0) {
            return storagePath + "_thumb";
        }
        return storagePath.substring(0, dotIndex) + "_thumb" + storagePath.substring(dotIndex);
    }
}
