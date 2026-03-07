package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.file.config.FileProperties;
import dowob.xyz.blog.module.file.model.FileErrorCode;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.model.dto.FileUploadResponse;
import dowob.xyz.blog.module.file.model.dto.QuotaResponse;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileService 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("FileService 單元測試")
class FileServiceTest {

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    @Mock
    private MinioClient minioClient;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private final FileProperties fileProperties = new FileProperties(
            Map.of("USER", DataSize.ofMegabytes(10), "AUTHOR", DataSize.ofMegabytes(500)),
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif")
    );

    @InjectMocks
    private FileServiceImpl fileService;

    /** 測試前設定 bucketName、minioEndpoint 及 FileProperties 真實實例 */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fileService, "bucketName", "test-bucket");
        ReflectionTestUtils.setField(fileService, "minioEndpoint", "http://localhost:9000");
        ReflectionTestUtils.setField(fileService, "fileProperties", fileProperties);
    }

    /** 上傳相關測試 */
    @Nested
    @DisplayName("uploadFile 測試")
    class UploadFileTests {

        /** 驗證有效 JPEG 圖片上傳成功並返回回應 */
        @Test
        @DisplayName("uploadFile_withValidImage_returnsResponse")
        void uploadFile_withValidImage_returnsResponse() throws Exception {
            byte[] jpegBytes = new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
                0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xD9
            };
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UUID uploaderId = UUID.randomUUID();
            FileUploadResponse response = fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, uploaderId, "AUTHOR");
            assertThat(response).isNotNull();
            assertThat(response.getId()).isNotNull();
            verify(fileMetadataRepository).save(any(FileMetadata.class));
            verify(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));
        }

        /** 驗證非圖片 MIME 類型應拋出 INVALID_FILE_TYPE 錯誤 */
        @Test
        @DisplayName("uploadFile_withInvalidMimeType_throwsBusinessException")
        void uploadFile_withInvalidMimeType_throwsBusinessException() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.pdf", "application/pdf",
                    new byte[]{0x25, 0x50, 0x44, 0x46});
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            UUID uploaderId = UUID.randomUUID();
            assertThatThrownBy(() -> fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, uploaderId, "AUTHOR"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(FileErrorCode.INVALID_FILE_TYPE.getMessage());
        }

        /** 驗證超過 5MB 限制應拋出 FILE_TOO_LARGE 錯誤 */
        @Test
        @DisplayName("uploadFile_withFileTooLarge_throwsBusinessException")
        void uploadFile_withFileTooLarge_throwsBusinessException() {
            byte[] largeBytes = new byte[6 * 1024 * 1024];
            largeBytes[0] = (byte) 0xFF; largeBytes[1] = (byte) 0xD8;
            largeBytes[2] = (byte) 0xFF; largeBytes[3] = (byte) 0xE0;
            largeBytes[4] = 0x00; largeBytes[5] = 0x10;
            largeBytes[6] = 0x4A; largeBytes[7] = 0x46;
            largeBytes[8] = 0x49; largeBytes[9] = 0x46; largeBytes[10] = 0x00;
            MockMultipartFile file = new MockMultipartFile(
                    "file", "large.jpg", "image/jpeg", largeBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            UUID uploaderId = UUID.randomUUID();
            assertThatThrownBy(() -> fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, uploaderId, "AUTHOR"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(FileErrorCode.FILE_TOO_LARGE.getMessage());
        }

        /** 驗證配額超限應拋出 QUOTA_EXCEEDED 錯誤 */
        @Test
        @DisplayName("uploadFile_withQuotaExceeded_throwsBusinessException")
        void uploadFile_withQuotaExceeded_throwsBusinessException() {
            byte[] jpegBytes = new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
                0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xD9
            };
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(10L * 1024 * 1024);
            UUID uploaderId = UUID.randomUUID();
            assertThatThrownBy(() -> fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, uploaderId, "USER"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(FileErrorCode.QUOTA_EXCEEDED.getMessage());
        }
    }

    /** 刪除相關測試 */
    @Nested
    @DisplayName("deleteFile 測試")
    class DeleteFileTests {

        /** 驗證檔案擁有者可成功刪除檔案 */
        @Test
        @DisplayName("deleteFile_byOwner_succeeds")
        void deleteFile_byOwner_succeeds() throws Exception {
            UUID ownerId = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/test.jpg");
            metadata.setUploaderId(ownerId);
            metadata.setHasThumbnail(false);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            fileService.deleteFile(fileId, ownerId, false);
            verify(fileMetadataRepository).deleteById(fileId);
        }

        /** 驗證非擁有者且非管理員刪除應拋出 FILE_ACCESS_DENIED 錯誤 */
        @Test
        @DisplayName("deleteFile_byNonOwnerNonAdmin_throwsBusinessException")
        void deleteFile_byNonOwnerNonAdmin_throwsBusinessException() {
            UUID ownerId = UUID.randomUUID();
            UUID otherUserId = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/test.jpg");
            metadata.setUploaderId(ownerId);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            assertThatThrownBy(() -> fileService.deleteFile(fileId, otherUserId, false))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(FileErrorCode.FILE_ACCESS_DENIED.getMessage());
        }

        /** 驗證刪除有縮圖的檔案時同時刪除縮圖 */
        @Test
        @DisplayName("deleteFile_withThumbnail_deletesThumb")
        void deleteFile_withThumbnail_deletesThumb() throws Exception {
            UUID ownerId = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/uuid.jpg");
            metadata.setUploaderId(ownerId);
            metadata.setHasThumbnail(true);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            fileService.deleteFile(fileId, ownerId, false);
            ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
            verify(minioClient, times(2)).removeObject(captor.capture());
        }
    }

    /** 配額查詢測試 */
    @Nested
    @DisplayName("getQuota 測試")
    class GetQuotaTests {

        /** 驗證 USER 角色的配額上限為 10MB */
        @Test
        @DisplayName("getQuota_forUser_returnsCorrectLimits")
        void getQuota_forUser_returnsCorrectLimits() {
            UUID userId = UUID.randomUUID();
            long usedBytes = 3 * 1024 * 1024L;
            when(fileMetadataRepository.sumSizeByUploaderId(userId)).thenReturn(usedBytes);
            QuotaResponse quota = fileService.getQuota(userId, "USER");
            long expectedLimit = 10L * 1024 * 1024;
            assertThat(quota.getLimitBytes()).isEqualTo(expectedLimit);
            assertThat(quota.getUsedBytes()).isEqualTo(usedBytes);
            assertThat(quota.getRemainingBytes()).isEqualTo(expectedLimit - usedBytes);
        }
    }

    /** Issue F-2: InputStream 只允許單次讀取的相容性測試 */
    @Nested
    @DisplayName("uploadFile InputStream 單次讀取測試 (F-2)")
    class SingleStreamReadTests {

        /**
         * 驗證 uploadFile 在 InputStream 只能讀取一次的限制下仍能正常運作
         * Red: 目前實作呼叫 getInputStream() 多次，此測試會失敗
         */
        @Test
        @DisplayName("uploadFile_withSingleReadStream_succeeds")
        void uploadFile_withSingleReadStream_succeeds() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            SingleReadMultipartFile file = new SingleReadMultipartFile("test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FileUploadResponse response = fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            assertThat(response).isNotNull();
            assertThat(response.getId()).isNotNull();
        }
    }

    /** Issue F-3: DB 失敗時補償刪除 MinIO 檔案 */
    @Nested
    @DisplayName("uploadFile 補償刪除測試 (F-3)")
    class CompensationTests {

        /**
         * 驗證 DB save 失敗時，補償刪除 MinIO 已上傳的檔案
         * Red: 目前實作不呼叫 removeObject，此測試會失敗
         */
        @Test
        @DisplayName("uploadFile_whenDbSaveFails_compensatesMinioDelete")
        void uploadFile_whenDbSaveFails_compensatesMinioDelete() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenThrow(new RuntimeException("DB failure"));

            assertThatThrownBy(() ->
                    fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR"))
                    .isInstanceOf(RuntimeException.class);

            verify(minioClient).removeObject(any(RemoveObjectArgs.class));
        }

        /**
         * 驗證 MQ convertAndSend 失敗時，補償刪除 MinIO 已上傳的檔案且異常正確傳播
         */
        @Test
        @DisplayName("uploadFile_whenMqFails_compensatesMinioDelete")
        void uploadFile_whenMqFails_compensatesMinioDelete() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            org.mockito.Mockito.doThrow(new RuntimeException("MQ failure"))
                    .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), (Object) any());

            assertThatThrownBy(() ->
                    fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("MQ failure");

            verify(minioClient).removeObject(any(RemoveObjectArgs.class));
        }

        /**
         * 驗證 DB save 成功時，不呼叫補償刪除
         */
        @Test
        @DisplayName("uploadFile_whenDbSaveSucceeds_noCompensation")
        void uploadFile_whenDbSaveSucceeds_noCompensation() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        }
    }

    /** Issue F-4: originalName null 檢查與長度截斷 */
    @Nested
    @DisplayName("uploadFile originalName 驗證測試 (F-4)")
    class OriginalNameValidationTests {

        /**
         * 驗證 null filename 使用預設名稱 "unnamed"
         * Red: 目前實作 setOriginalName(null)，此測試會失敗
         */
        @Test
        @DisplayName("uploadFile_withNullFilename_usesDefaultName")
        void uploadFile_withNullFilename_usesDefaultName() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", null, "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getOriginalName()).isEqualTo("unnamed");
        }

        /**
         * 驗證超過 255 字元的 filename 被截斷
         * Red: 目前實作儲存完整長名，此測試會失敗
         */
        @Test
        @DisplayName("uploadFile_withLongFilename_truncatesTo255")
        void uploadFile_withLongFilename_truncatesTo255() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            String longName = "a".repeat(300) + ".jpg";
            MockMultipartFile file = new MockMultipartFile("file", longName, "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getOriginalName()).hasSizeLessThanOrEqualTo(255);
        }
    }

    /** 最小合法 JPEG bytes（Magic number + EOI） */
    private static byte[] minimalJpegBytes() {
        return new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xD9
        };
    }

    /**
     * 僅允許一次 getInputStream() 呼叫的 MultipartFile wrapper（測試 F-2 用）
     */
    static class SingleReadMultipartFile extends MockMultipartFile {
        private int readCount = 0;

        SingleReadMultipartFile(String originalFilename, String contentType, byte[] content) {
            super("file", originalFilename, contentType, content);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (readCount++ > 0) {
                throw new IOException("InputStream already consumed - single read only");
            }
            return super.getInputStream();
        }
    }
}
