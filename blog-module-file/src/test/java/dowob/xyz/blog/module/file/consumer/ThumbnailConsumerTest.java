package dowob.xyz.blog.module.file.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.file.event.ImageUploadedEvent;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
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

    @Mock
    private Channel channel;

    /** 真實的 ack/nack 共用包裝（Spy 實例，非 Mock，使 ack/nack 與計數走真實路徑） */
    @Spy
    private ConsumerExecutor consumerExecutor = new ConsumerExecutor();

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

        thumbnailConsumer.handleImageUploaded(event, channel, 1L);

        verify(minioClient, never()).getObject(any(GetObjectArgs.class));
        verify(channel).basicAck(1L, false);
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

        thumbnailConsumer.handleImageUploaded(event, channel, 42L);

        verify(channel).basicNack(42L, false, false);
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

        thumbnailConsumer.handleImageUploaded(event, channel, 99L);

        /* 只做單欄位 UPDATE，絕不 save 整個實體（否則處理期間被 bindToArticle 寫入的 article_uuid 會被蓋回舊值） */
        verify(fileMetadataRepository).markThumbnailGenerated(fileId);
        verify(fileMetadataRepository, never()).save(any());
        verify(channel).basicAck(99L, false);
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

        thumbnailConsumer.handleImageUploaded(event, channel, 50L);

        verify(fileMetadataRepository, never()).save(any());
        /* 單欄位 UPDATE 對已不存在的檔案影響 0 列（mock 預設回 0），屬正常 no-op，仍 ack */
        verify(fileMetadataRepository).markThumbnailGenerated(fileId);
        verify(channel).basicAck(50L, false);
    }

    /**
     * 驗證處理失敗且 basicNack 也拋出 IOException 時不會拋出未處理例外
     */
    @Test
    @DisplayName("handleImageUploaded_onFailure_nackAlsoFails_doesNotThrow")
    void handleImageUploaded_onFailure_nackAlsoFails_doesNotThrow() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        ImageUploadedEvent event = new ImageUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "files/error.jpg", "image/jpeg");

        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("MinIO connection failed"));
        doThrow(new IOException("channel closed")).when(channel).basicNack(eq(77L), eq(false), eq(false));

        thumbnailConsumer.handleImageUploaded(event, channel, 77L);

        verify(channel).basicNack(77L, false, false);
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

        thumbnailConsumer.handleImageUploaded(event, channel, 60L);

        verify(channel).basicAck(60L, false);
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
            thumbnailConsumer.handleImageUploaded(event, channel, 5L);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, ThumbnailConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ThumbnailConsumer.CONSUMER_NAME)).isZero();
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：處理失敗 → failed／dead_lettered 指標加一，且 ConsumerExecutor 的 ERROR 日誌保留業務上下文
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleImageUploaded：失敗時計入 failed／dead_lettered 指標，ERROR 日誌保留業務上下文")
    void handleImageUploaded_onFailure_recordsMetricsAndLogsContext() throws Exception {
        ReflectionTestUtils.setField(thumbnailConsumer, "bucketName", "test-bucket");
        UUID fileId = UUID.randomUUID();
        ImageUploadedEvent event = new ImageUploadedEvent(UUID.randomUUID(), fileId, "files/error.jpg", "image/jpeg");
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("MinIO connection failed"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> thumbnailConsumer.handleImageUploaded(event, channel, 6L));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ThumbnailConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, ThumbnailConsumer.CONSUMER_NAME)).isEqualTo(1.0);
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

        thumbnailConsumer.handleImageUploaded(event, channel, 11L);

        verify(minioClient, never()).getObject(any(GetObjectArgs.class));
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
        verify(fileMetadataRepository, never()).save(any());
        verify(fileMetadataRepository, never()).markThumbnailGenerated(any());
        verify(channel).basicAck(11L, false);
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

        thumbnailConsumer.handleImageUploaded(event, channel, 21L);
        thumbnailConsumer.handleImageUploaded(event, channel, 22L);

        verify(minioClient, times(1)).getObject(any(GetObjectArgs.class));
        verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));
        verify(fileMetadataRepository, times(1)).markThumbnailGenerated(fileId);
        verify(channel).basicAck(21L, false);
        verify(channel).basicAck(22L, false);
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

        thumbnailConsumer.handleImageUploaded(event, channel, 31L);

        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(fileMetadataRepository).markThumbnailGenerated(fileId);
        verify(channel).basicAck(31L, false);
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

        thumbnailConsumer.handleImageUploaded(event, channel, 41L);

        verify(fileMetadataRepository, never()).markThumbnailGenerated(any());
        verify(fileMetadataRepository, never()).save(any());
        verify(channel).basicNack(41L, false, false);
        verify(channel, never()).basicAck(eq(41L), eq(false));
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

        thumbnailConsumer.handleImageUploaded(event, channel, 42L);

        verify(fileMetadataRepository, never()).markThumbnailGenerated(any());
        verify(channel).basicNack(42L, false, false);
        verify(channel, never()).basicAck(eq(42L), eq(false));
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
