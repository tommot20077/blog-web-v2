package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.exception.BusinessException;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    private FileServiceImpl fileService;

    /** 測試前設定 bucketName 與 minioEndpoint */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fileService, "bucketName", "test-bucket");
        ReflectionTestUtils.setField(fileService, "minioEndpoint", "http://localhost:9000");
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
}
