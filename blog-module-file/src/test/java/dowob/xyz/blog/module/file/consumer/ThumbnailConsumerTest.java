package dowob.xyz.blog.module.file.consumer;

import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.file.config.FileProperties;
import dowob.xyz.blog.module.file.event.ImageUploadedEvent;
import dowob.xyz.blog.module.file.support.TestImages;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ThumbnailConsumer 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("ThumbnailConsumer 單元測試")
class ThumbnailConsumerTest {

    @Mock
    private MinioClient minioClient;

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    /** 真實的 ack/nack 共用包裝（Spy 實例，非 Mock，使 ack/nack 與計數走真實路徑） */
    @Spy
    private ConsumerExecutor consumerExecutor = new ConsumerExecutor();

    /** 真實的設定實例（圖片總像素上限預設 100MP） */
    @Spy
    private FileProperties fileProperties = new FileProperties(java.util.Map.of(), java.util.Set.of(), null, null, null, null);

    @InjectMocks
    private ThumbnailConsumer thumbnailConsumer;

    /**
     * 驗證收到非圖片類型事件時跳過縮圖處理，並發送 ACK
     */
    @Test
    @DisplayName("handleImageUploaded_withNonImageType_skips_andAcks")
    void handleImageUploaded_withNonImageType_skips() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "files/test.pdf", "application/pdf");

        thumbnailConsumer.handleImageUploaded(event);

        verify(minioClient, never()).getObject(any(GetObjectArgs.class));
    }

    /**
     * 驗證處理失敗時呼叫 basicNack
     */
    @Test
    @DisplayName("handleImageUploaded_onFailure_callsBasicNack")
    void handleImageUploaded_onFailure_callsBasicNack() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "files/error.jpg", "image/jpeg");

        /** minioClient.getObject 拋出例外以模擬失敗 */
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("MinIO connection failed"));

        assertThatThrownBy(() -> thumbnailConsumer.handleImageUploaded(event)).isInstanceOf(RuntimeException.class);
    }

    /**
     * 驗證收到有效圖片事件時產生縮圖並更新 DB
     */
    @Test
    @DisplayName("handleImageUploaded_withValidImage_createsThumbnail")
    void handleImageUploaded_withValidImage_createsThumbnail() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");

        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");

        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setStoragePath("articles/2024/01/01/uuid.jpg");
        metadata.setHasThumbnail(false);

        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
        when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        /** 載入測試用真實 JPEG 資源 */
        InputStream jpegStream = getClass().getResourceAsStream("/test.jpg");
        assertThat(jpegStream).isNotNull();
        byte[] jpegBytes = jpegStream.readAllBytes();

        GetObjectResponse mockResponse = org.mockito.Mockito.mock(GetObjectResponse.class);
        /** 使 GetObjectResponse 的 read 行為與真實 ByteArrayInputStream 相同 */
        ByteArrayInputStream bais = new ByteArrayInputStream(jpegBytes);
        when(mockResponse.read(any(byte[].class), any(int.class), any(int.class)))
                .thenAnswer(inv -> bais.read(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(mockResponse.read())
                .thenAnswer(inv -> bais.read());
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        thumbnailConsumer.handleImageUploaded(event);

        /* 只做單欄位 UPDATE，絕不 save 整個實體（否則處理期間被 bindToArticle 寫入的 article_uuid 會被蓋回舊值） */
        verify(fileMetadataRepository).markThumbnailGenerated(fileId);
        verify(fileMetadataRepository, never()).save(any());
    }

    /**
     * 驗證 fileMetadataRepository.findById 回傳 empty 時不呼叫 save
     */
    @Test
    @DisplayName("handleImageUploaded_withValidImage_butMetadataNotFound_doesNotSave")
    void handleImageUploaded_withValidImage_butMetadataNotFound_doesNotSave() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");

        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");

        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());

        InputStream jpegStream = getClass().getResourceAsStream("/test.jpg");
        assertThat(jpegStream).isNotNull();
        byte[] jpegBytes = jpegStream.readAllBytes();

        GetObjectResponse mockResponse = org.mockito.Mockito.mock(GetObjectResponse.class);
        ByteArrayInputStream bais = new ByteArrayInputStream(jpegBytes);
        when(mockResponse.read(any(byte[].class), any(int.class), any(int.class)))
                .thenAnswer(inv -> bais.read(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(mockResponse.read())
                .thenAnswer(inv -> bais.read());
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        thumbnailConsumer.handleImageUploaded(event);

        verify(fileMetadataRepository, never()).save(any());
        /* 單欄位 UPDATE 對已不存在的檔案影響 0 列（mock 預設回 0），屬正常 no-op，仍 ack */
        verify(fileMetadataRepository).markThumbnailGenerated(fileId);
    }

    /**
     * 驗證路徑無副檔名時 extractExtension 回傳 "jpg" 且 buildThumbPath 附加 _thumb
     */
    @Test
    @DisplayName("handleImageUploaded_withPathWithoutExtension_usesJpgDefault")
    void handleImageUploaded_withPathWithoutExtension_usesJpgDefault() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");

        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/noext", "image/jpeg");

        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());

        InputStream jpegStream = getClass().getResourceAsStream("/test.jpg");
        assertThat(jpegStream).isNotNull();
        byte[] jpegBytes = jpegStream.readAllBytes();

        GetObjectResponse mockResponse = org.mockito.Mockito.mock(GetObjectResponse.class);
        ByteArrayInputStream bais = new ByteArrayInputStream(jpegBytes);
        when(mockResponse.read(any(byte[].class), any(int.class), any(int.class)))
                .thenAnswer(inv -> bais.read(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(mockResponse.read())
                .thenAnswer(inv -> bais.read());
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        thumbnailConsumer.handleImageUploaded(event);
    }

    /**
     * FILE-03：縮圖輸出格式來自 contentType 白名單，storagePath 副檔名（如 .php）不得進入 outputFormat
     */
    @Test
    @DisplayName("handleImageUploaded_withHostileExtensionInPath_usesWhitelistedFormatFromContentType")
    void handleImageUploaded_withHostileExtensionInPath_usesWhitelistedFormatFromContentType() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.php", "image/jpeg");
        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());
        byte[] jpegBytes = getClass().getResourceAsStream("/test.jpg").readAllBytes();
        GetObjectResponse mockResponse = org.mockito.Mockito.mock(GetObjectResponse.class);
        ByteArrayInputStream bais = new ByteArrayInputStream(jpegBytes);
        when(mockResponse.read(any(byte[].class), any(int.class), any(int.class)))
                .thenAnswer(inv -> bais.read(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(mockResponse.read()).thenAnswer(inv -> bais.read());
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        thumbnailConsumer.handleImageUploaded(event);

        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(fileMetadataRepository).markThumbnailGenerated(fileId);
    }

    /**
     * FILE-03：contentType 不在圖片白名單（如 image/svg+xml）時直接跳過，不下載也不產生縮圖
     */
    @Test
    @DisplayName("handleImageUploaded_withNonWhitelistedImageType_skipsWithoutDownload")
    void handleImageUploaded_withNonWhitelistedImageType_skipsWithoutDownload() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "articles/2024/01/01/uuid.svg", "image/svg+xml");

        thumbnailConsumer.handleImageUploaded(event);

        verify(minioClient, never()).getObject(any(GetObjectArgs.class));
        verify(fileMetadataRepository, never()).markThumbnailGenerated(any());
    }

    /** 建立回傳真實 JPEG 的 getObject 串流 mock，並讓 mark 回傳指定列數 */
    private void stubDownloadAndMark(UUID fileId, int markResult) throws Exception {
        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());
        byte[] jpegBytes = getClass().getResourceAsStream("/test.jpg").readAllBytes();
        GetObjectResponse mockResponse = org.mockito.Mockito.mock(GetObjectResponse.class);
        ByteArrayInputStream bais = new ByteArrayInputStream(jpegBytes);
        when(mockResponse.read(any(byte[].class), any(int.class), any(int.class)))
                .thenAnswer(inv -> bais.read(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(mockResponse.read()).thenAnswer(inv -> bais.read());
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);
        when(fileMetadataRepository.markThumbnailGenerated(fileId)).thenReturn(markResult);
    }

    /** 讓 getObject 回傳指定位元組的串流 */
    private void stubDownload(byte[] bytes) throws Exception {
        GetObjectResponse mockResponse = org.mockito.Mockito.mock(GetObjectResponse.class);
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        when(mockResponse.read(any(byte[].class), any(int.class), any(int.class)))
                .thenAnswer(inv -> bais.read(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(mockResponse.read()).thenAnswer(inv -> bais.read());
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);
    }

    /**
     * FILE-04（A6）：縮圖端再擋一次——總像素超過上限的圖不解碼、不產生縮圖，記 WARN 後正常結束（ack，不重試進 DLQ）
     */
    @Test
    @DisplayName("handleImageUploaded_withImageOverPixelLimit_skipsThumbnail_andAcksWithoutThrowing")
    void handleImageUploaded_withImageOverPixelLimit_skipsThumbnail() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.png", "image/png");
        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());
        stubDownload(TestImages.solidPng(12000, 9000));

        org.assertj.core.api.Assertions.assertThatCode(() -> thumbnailConsumer.handleImageUploaded(event))
                .doesNotThrowAnyException();

        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
        verify(fileMetadataRepository, never()).markThumbnailGenerated(any());
    }

    /**
     * FILE-04：header 讀不到寬高的檔案不得進入解碼（安全性由程式碼保證，不依賴「兩邊用同一套 reader」的假設）——
     * 跳過縮圖、不外拋（ack），不呼叫 Thumbnails 也不寫入縮圖物件
     */
    @Test
    @DisplayName("handleImageUploaded_whenHeaderUnreadable_skipsThumbnailWithoutDecoding")
    void handleImageUploaded_whenHeaderUnreadable_skipsThumbnail() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");
        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());
        /* ImageIO 認得 PNG 簽章、但 header 被截斷而讀不出尺寸 */
        byte[] png = TestImages.solidPng(50, 50);
        stubDownload(java.util.Arrays.copyOf(png, 12));

        try (org.mockito.MockedStatic<Thumbnails> thumbnails = org.mockito.Mockito.mockStatic(Thumbnails.class)) {
            org.assertj.core.api.Assertions.assertThatCode(() -> thumbnailConsumer.handleImageUploaded(event))
                    .doesNotThrowAnyException();

            thumbnails.verifyNoInteractions();
        }
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
        verify(fileMetadataRepository, never()).markThumbnailGenerated(any());
    }

    /**
     * FILE-04（A6）：上限來自設定屬性；縮圖端用同一個值
     */
    @Test
    @DisplayName("handleImageUploaded_withConfiguredSmallerPixelLimit_skipsThumbnailAccordingToProperty")
    void handleImageUploaded_withConfiguredSmallerPixelLimit_skipsThumbnail() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        ReflectionTestUtils.setField(thumbnailConsumer, "fileProperties",
                new FileProperties(java.util.Map.of(), java.util.Set.of(), 99L, null, null, null));
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");
        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());
        stubDownload(getClass().getResourceAsStream("/test.jpg").readAllBytes());

        thumbnailConsumer.handleImageUploaded(event);

        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
    }

    /**
     * DATA-14：檔案在縮圖處理期間被刪除（mark 影響 0 列）→ 補刪剛寫入的縮圖物件，不留孤兒
     */
    @Test
    @DisplayName("handleImageUploaded_whenMarkAffectsZeroRows_removesJustWrittenThumbnail")
    void handleImageUploaded_whenMarkAffectsZeroRows_removesJustWrittenThumbnail() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");
        stubDownloadAndMark(fileId, 0);

        thumbnailConsumer.handleImageUploaded(event);

        org.mockito.ArgumentCaptor<PutObjectArgs> put = org.mockito.ArgumentCaptor.forClass(PutObjectArgs.class);
        org.mockito.ArgumentCaptor<io.minio.RemoveObjectArgs> remove =
                org.mockito.ArgumentCaptor.forClass(io.minio.RemoveObjectArgs.class);
        verify(minioClient).putObject(put.capture());
        verify(minioClient).removeObject(remove.capture());
        assertThat(remove.getValue().object()).isEqualTo(put.getValue().object())
                .isEqualTo("articles/2024/01/01/uuid_thumb.jpg");
        assertThat(remove.getValue().bucket()).isEqualTo("test-bucket");
    }

    /**
     * DATA-14：補刪失敗只記 WARN、不拋例外（避免無意義進 DLQ）
     */
    @Test
    @DisplayName("handleImageUploaded_whenCleanupRemoveFails_doesNotThrow")
    void handleImageUploaded_whenCleanupRemoveFails_doesNotThrow() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");
        stubDownloadAndMark(fileId, 0);
        doThrow(new RuntimeException("minio down")).when(minioClient).removeObject(any(io.minio.RemoveObjectArgs.class));

        org.assertj.core.api.Assertions.assertThatCode(() -> thumbnailConsumer.handleImageUploaded(event))
                .doesNotThrowAnyException();
        verify(minioClient).removeObject(any(io.minio.RemoveObjectArgs.class));
    }

    /**
     * DATA-14：mark 成功（影響 1 列）時不得刪除縮圖
     */
    @Test
    @DisplayName("handleImageUploaded_whenMarkSucceeds_doesNotRemoveThumbnail")
    void handleImageUploaded_whenMarkSucceeds_doesNotRemoveThumbnail() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");
        stubDownloadAndMark(fileId, 1);

        thumbnailConsumer.handleImageUploaded(event);

        verify(minioClient, never()).removeObject(any(io.minio.RemoveObjectArgs.class));
    }

    /**
     * 情境：成功處理 → 經 ConsumerExecutor 計入 processed 指標（consumer tag 為 CONSUMER_NAME）
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleImageUploaded：成功時 ConsumerExecutor 計入 processed 指標")
    void handleImageUploaded_success_recordsProcessedMetric() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        ImageUploadedEvent event = new ImageUploadedEvent(UUID.randomUUID(), UUID.randomUUID(), "files/test.pdf", "application/pdf");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            thumbnailConsumer.handleImageUploaded(event);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, ThumbnailConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ThumbnailConsumer.CONSUMER_NAME)).isZero();
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：處理失敗 → failed 指標加一，且 ConsumerExecutor 的 ERROR 日誌保留業務上下文
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleImageUploaded：失敗時計入 failed 指標，ERROR 日誌保留業務上下文")
    void handleImageUploaded_onFailure_recordsMetricsAndLogsContext() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(UUID.randomUUID(), fileId, "files/error.jpg", "image/jpeg");
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("MinIO connection failed"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> assertThatThrownBy(() -> thumbnailConsumer.handleImageUploaded(event)).isInstanceOf(RuntimeException.class));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ThumbnailConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, ThumbnailConsumer.CONSUMER_NAME)).as("dead_lettered 由容器層的 DeadLetterCountingErrorHandler 計，ConsumerExecutor 不計").isZero();
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains(fileId.toString());
            });
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：縮圖已產生（metadata.hasThumbnail 已為 true）→ 天然冪等，直接 ack，不下載、不上傳、不寫 DB。
     *
     * <p>這是重送（at-least-once）的第二次遞送：第一次已完成縮圖並寫入旗標，重做只是浪費 MinIO 流量。</p>
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleImageUploaded_whenThumbnailAlreadyGenerated_skipsWorkAndAcks")
    void handleImageUploaded_whenThumbnailAlreadyGenerated_skipsWorkAndAcks() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setHasThumbnail(true);
        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));

        thumbnailConsumer.handleImageUploaded(event);

        verify(minioClient, never()).getObject(any(GetObjectArgs.class));
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
        verify(fileMetadataRepository, never()).save(any());
        verify(fileMetadataRepository, never()).markThumbnailGenerated(any());
    }

    /**
     * 情境：同一事件（相同 eventId）遞送兩次 → 縮圖只產生與上傳一次，兩次皆 ack。
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleImageUploaded_redeliveredAfterSuccess_generatesThumbnailOnlyOnce")
    void handleImageUploaded_redeliveredAfterSuccess_generatesThumbnailOnlyOnce() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");
        /* 同一個 metadata 實例：第一次處理寫入旗標後，第二次 findById 讀到的就是已完成狀態 */
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setHasThumbnail(false);
        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
        when(fileMetadataRepository.markThumbnailGenerated(fileId)).thenAnswer(inv -> {
            metadata.setHasThumbnail(true);
            return 1;
        });
        when(minioClient.getObject(any(GetObjectArgs.class))).thenAnswer(inv -> jpegResponse());

        thumbnailConsumer.handleImageUploaded(event);
        thumbnailConsumer.handleImageUploaded(event);

        verify(minioClient, times(1)).getObject(any(GetObjectArgs.class));
        verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));
        verify(fileMetadataRepository, times(1)).markThumbnailGenerated(fileId);
    }

    /**
     * 情境：舊格式 in-flight 訊息（無 eventId，反序列化為 null）→ 仍照常處理，不因缺 eventId 而失敗。
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleImageUploaded_legacyEventWithoutEventId_stillGeneratesThumbnail")
    void handleImageUploaded_legacyEventWithoutEventId_stillGeneratesThumbnail() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                null, fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setHasThumbnail(false);
        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenAnswer(inv -> jpegResponse());

        thumbnailConsumer.handleImageUploaded(event);

        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(fileMetadataRepository).markThumbnailGenerated(fileId);
    }

    /**
     * 情境：下載原圖失敗 → 不得把縮圖標成完成（旗標更新完全不被呼叫），訊息 nack。
     *
     * <p>守住「失敗後重試仍會重做」：若旗標寫入被移到 finally，失敗的處理也會被標成完成，
     * 之後重送就被當成已完成而永遠沒有縮圖。</p>
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleImageUploaded_whenDownloadFails_doesNotMarkThumbnailGeneratedAndNacks")
    void handleImageUploaded_whenDownloadFails_doesNotMarkThumbnailGeneratedAndNacks() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setHasThumbnail(false);
        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("MinIO connection failed"));

        assertThatThrownBy(() -> thumbnailConsumer.handleImageUploaded(event)).isInstanceOf(RuntimeException.class);

        verify(fileMetadataRepository, never()).markThumbnailGenerated(any());
        verify(fileMetadataRepository, never()).save(any());
    }

    /**
     * 情境：縮圖上傳失敗（下載與縮圖都成功）→ 不得把縮圖標成完成，訊息 nack。
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleImageUploaded_whenUploadFails_doesNotMarkThumbnailGeneratedAndNacks")
    void handleImageUploaded_whenUploadFails_doesNotMarkThumbnailGeneratedAndNacks() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setHasThumbnail(false);
        when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenAnswer(inv -> jpegResponse());
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(new RuntimeException("MinIO put failed"));

        assertThatThrownBy(() -> thumbnailConsumer.handleImageUploaded(event)).isInstanceOf(RuntimeException.class);

        verify(fileMetadataRepository, never()).markThumbnailGenerated(any());
    }

    /**
     * 建立回傳測試用 JPEG 位元組的 MinIO 回應。
     *
     * @return 每次呼叫皆為全新串流的 {@link GetObjectResponse} mock
     * @throws IOException 讀取測試資源失敗時
     */
    private GetObjectResponse jpegResponse() throws IOException {
        InputStream jpegStream = getClass().getResourceAsStream("/test.jpg");
        assertThat(jpegStream).isNotNull();
        ByteArrayInputStream bais = new ByteArrayInputStream(jpegStream.readAllBytes());
        GetObjectResponse response = org.mockito.Mockito.mock(GetObjectResponse.class);
        when(response.read(any(byte[].class), any(int.class), any(int.class)))
                .thenAnswer(inv -> bais.read(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(response.read()).thenAnswer(inv -> bais.read());
        return response;
    }

    /**
     * 讀取指定 consumer 的指標計數，指標不存在時視為 0。
     *
     * @param registry     測試用 registry
     * @param metric       指標名稱
     * @param consumerName consumer 識別名稱（指標的 consumer tag）
     * @return 計數值
     */
    private static double count(SimpleMeterRegistry registry, String metric, String consumerName) {
        Counter counter = registry.find(metric).tag("consumer", consumerName).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * 執行動作並捕捉 {@link ConsumerExecutor} 輸出的日誌事件。
     *
     * @param action 要執行的動作
     * @return 捕捉到的日誌事件
     * @throws Exception 動作拋出的例外
     */
    private static List<LogEvent> captureExecutorLogs(ThrowingRunnable action) throws Exception {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        Logger logger = (Logger) LogManager.getLogger(ConsumerExecutor.class);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
        return appender.events;
    }

    /** 捕捉日誌事件的測試用 appender（專案日誌實作為 log4j2） */
    private static final class CapturingAppender extends AbstractAppender {

        /** 捕捉到的日誌事件 */
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("consumer-executor-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
