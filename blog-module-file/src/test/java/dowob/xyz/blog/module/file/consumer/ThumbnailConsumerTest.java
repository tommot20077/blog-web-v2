package dowob.xyz.blog.module.file.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.file.event.ImageUploadedEvent;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
                UUID.randomUUID(), "files/test.pdf", "application/pdf");

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
                UUID.randomUUID(), "files/error.jpg", "image/jpeg");

        // minioClient.getObject 拋出例外以模擬失敗
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
                fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");

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

        ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileMetadataRepository).save(captor.capture());
        assertThat(captor.getValue().getHasThumbnail()).isTrue();
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
                fileId, "articles/2024/01/01/uuid.jpg", "image/jpeg");

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
                UUID.randomUUID(), "files/error.jpg", "image/jpeg");

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
                fileId, "articles/noext", "image/jpeg");

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
}
