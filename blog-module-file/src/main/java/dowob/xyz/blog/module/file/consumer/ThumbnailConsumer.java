package dowob.xyz.blog.module.file.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.file.event.ImageUploadedEvent;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
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
 * <p>冪等性：以 {@code has_thumbnail} 旗標做天然冪等，重送的事件在旗標已為 true 時直接 ack。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailConsumer {

    /** consumer 識別名（作為 ConsumerExecutor 指標與日誌的 consumer tag） */
    public static final String CONSUMER_NAME = "file.thumbnail";

    /**
     * MinIO 客戶端
     */
    private final MinioClient minioClient;

    /**
     * 檔案元資料 Repository
     */
    private final FileMetadataRepository fileMetadataRepository;

    /**
     * MQ consumer 共用 ack/nack 包裝與計數
     */
    private final ConsumerExecutor consumerExecutor;

    /**
     * MinIO 儲存桶名稱
     */
    @Value("${minio.bucket-name}")
    private String bucketName;

    /**
     * 處理圖片上傳事件，產生縮圖
     *
     * @param event       圖片上傳事件
     * @param channel     RabbitMQ Channel，交由 {@link ConsumerExecutor} 執行 ack／nack
     * @param deliveryTag 消息投遞標籤
     */
    @RabbitListener(queues = "file.thumbnail")
    public void handleImageUploaded(ImageUploadedEvent event,
                                    Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        consumerExecutor.execute(CONSUMER_NAME, () -> "fileId=" + event.fileId() + ", eventId=" + event.eventId(), channel, deliveryTag, () -> {
            if (!event.contentType().startsWith("image/")) {
                log.debug("非圖片類型，跳過縮圖處理: {}", event.contentType());
                return;
            }

            /*
             * 天然冪等（不使用 IdempotencyService）：縮圖是否完成已由 file_metadata.has_thumbnail 記錄，
             * 重送時旗標為 true 就直接 ack，不再下載、產生與上傳。
             * 不用 processed_events 的原因：它在處理「前」標記，處理中途失敗（MinIO 暫時不可用）後
             * 重試會被誤判為已處理而永遠漏做縮圖；旗標是處理「後」才寫，失敗重試仍會重做。
             * 兩次遞送若在旗標寫入前併發，結果只是同一個縮圖物件被覆寫一次（內容相同），無害。
             * 舊格式 in-flight 訊息的 eventId 為 null，本邏輯不依賴 eventId，故照常處理。
             */
            if (fileMetadataRepository.findById(event.fileId()).map(m -> Boolean.TRUE.equals(m.getHasThumbnail())).orElse(false)) {
                log.debug("縮圖已存在，跳過重複處理: fileId={}", event.fileId());
                return;
            }

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

            /*
             * 只做單欄位 UPDATE，不 save 開頭讀出的舊實體：縮圖處理耗時，save 會以舊值覆寫所有欄位，
             * 把處理期間 bindToArticle 寫入的 article_uuid 蓋回 null（已發布文章的圖片變 403）。
             * 檔案若在處理期間被刪除，影響 0 列，屬正常 no-op。
             */
            fileMetadataRepository.markThumbnailGenerated(event.fileId());

            log.info("縮圖產生成功: {}", thumbPath);
        });
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
