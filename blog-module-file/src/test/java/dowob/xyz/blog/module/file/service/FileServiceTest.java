package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.SystemException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.file.config.FileProperties;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.FileErrorCode;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.model.dto.FileUploadResponse;
import dowob.xyz.blog.module.file.model.dto.QuotaResponse;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.apache.tika.Tika;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ArticleFacade articleFacade;

    @Mock
    private UserFacade userFacade;

    private final FileProperties fileProperties = new FileProperties(
            Map.of("USER", DataSize.ofMegabytes(10), "AUTHOR", DataSize.ofMegabytes(500)),
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif")
    );

    @InjectMocks
    private FileServiceImpl fileService;

    /**
     * 測試前設定 bucketName 及 FileProperties 真實實例。
     *
     * <p>B4：{@code minioEndpoint} 欄位已隨 uploadFile 改回傳相對路徑（不再組 MinIO 直連網址）而移除，
     * 故不再需要於此設定。</p>
     */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fileService, "bucketName", "test-bucket");
        ReflectionTestUtils.setField(fileService, "fileProperties", fileProperties);
        /** 讓 transactionTemplate.executeWithoutResult() 實際執行 callback */
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> consumer = inv.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
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

        /** 驗證 MinIO 刪除失敗時拋出 SystemException（→HTTP 500），讓 @Transactional 可回滾 DB */
        @Test
        @DisplayName("deleteFile_whenMinioFails_throwsSystemException")
        void deleteFile_whenMinioFails_throwsSystemException() throws Exception {
            UUID ownerId = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/test.jpg");
            metadata.setUploaderId(ownerId);
            metadata.setHasThumbnail(false);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            doThrow(new RuntimeException("MinIO error")).when(minioClient).removeObject(any(RemoveObjectArgs.class));

            assertThatThrownBy(() -> fileService.deleteFile(fileId, ownerId, false))
                    .isInstanceOf(SystemException.class)
                    .hasMessageContaining(CommonErrorCode.STORAGE_ERROR.getMessage());
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
         * 驗證 MQ convertAndSend 失敗時（best-effort），上傳仍成功、不觸發補償、回傳非 null 結果
         */
        @Test
        @DisplayName("uploadFile_whenMqFails_uploadStillSucceeds")
        void uploadFile_whenMqFails_uploadStillSucceeds() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            org.mockito.Mockito.doThrow(new RuntimeException("MQ failure"))
                    .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), (Object) any());

            FileUploadResponse response = fileService.uploadFile(
                    file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            assertThat(response).isNotNull();
            assertThat(response.getId()).isNotNull();
            verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
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

    /** =========================== */
    /** 邊界測試：uploadFile 邊界分支 */
    /** =========================== */

    /** uploadFile：file.getBytes() 拋出 IOException */
    @Nested
    @DisplayName("uploadFile getBytes IOException 測試")
    class UploadFileGetBytesExceptionTests {

        @Test
        @DisplayName("uploadFile_whenGetBytesFails_throwsSystemException")
        void uploadFile_whenGetBytesFails_throwsSystemException() throws Exception {
            MultipartFile brokenFile = org.mockito.Mockito.mock(MultipartFile.class);
            when(brokenFile.getBytes()).thenThrow(new IOException("disk read error"));
            when(brokenFile.getOriginalFilename()).thenReturn("test.jpg");

            assertThatThrownBy(() ->
                    fileService.uploadFile(brokenFile, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR"))
                    .isInstanceOf(SystemException.class)
                    .hasMessageContaining(CommonErrorCode.FILE_IO_ERROR.getMessage());
        }
    }

    /** uploadFile：usageType = AVATAR → storagePath prefix 應為 avatars */
    @Nested
    @DisplayName("uploadFile AVATAR 類型路徑前綴測試")
    class UploadFileAvatarPrefixTests {

        @Test
        @DisplayName("uploadFile_withAvatarUsageType_usesAvatarPrefix")
        void uploadFile_withAvatarUsageType_usesAvatarPrefix() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FileUploadResponse response = fileService.uploadFile(file, UsageType.AVATAR, UUID.randomUUID(), "USER");

            assertThat(response).isNotNull();
            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getStoragePath()).startsWith("avatars/");
        }

        @Test
        @DisplayName("uploadFile_withArticleCoverUsageType_usesArticlesPrefix")
        void uploadFile_withArticleCoverUsageType_usesArticlesPrefix() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FileUploadResponse response = fileService.uploadFile(file, UsageType.ARTICLE_COVER, UUID.randomUUID(), "USER");

            assertThat(response).isNotNull();
            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getStoragePath()).startsWith("articles/");
        }
    }

    /** uploadFile：Tika MIME 偵測拋出 IOException → SystemException(FILE_IO_ERROR) */
    @Nested
    @DisplayName("uploadFile MIME 偵測失敗測試")
    class UploadFileMimeDetectionFailTests {

        @Test
        @DisplayName("uploadFile_whenMimeDetectionFails_throwsSystemException")
        void uploadFile_whenMimeDetectionFails_throwsSystemException() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            /** 注入會拋 IOException 的 Tika mock，觸發 detectMimeType 的 catch 分支 */
            Tika brokenTika = org.mockito.Mockito.mock(Tika.class);
            when(brokenTika.detect(any(InputStream.class), any(String.class)))
                    .thenThrow(new IOException("tika detect error"));
            ReflectionTestUtils.setField(fileService, "tika", brokenTika);

            assertThatThrownBy(() ->
                    fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR"))
                    .isInstanceOf(SystemException.class)
                    .hasMessageContaining(CommonErrorCode.FILE_IO_ERROR.getMessage());
        }
    }

    /** uploadFile：MinIO putObject 拋出 Exception */
    @Nested
    @DisplayName("uploadFile MinIO putObject 失敗測試")
    class UploadFileMinioFailTests {

        @Test
        @DisplayName("uploadFile_whenMinioPutFails_throwsSystemException")
        void uploadFile_whenMinioPutFails_throwsSystemException() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            doThrow(new RuntimeException("MinIO connection refused"))
                    .when(minioClient).putObject(any(PutObjectArgs.class));

            assertThatThrownBy(() ->
                    fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR"))
                    .isInstanceOf(SystemException.class)
                    .hasMessageContaining(CommonErrorCode.STORAGE_ERROR.getMessage());
        }
    }

    /** uploadFile：ADMIN 角色配額為 Long.MAX_VALUE，不受配額限制 */
    @Nested
    @DisplayName("uploadFile ADMIN 角色無配額限制測試")
    class UploadFileAdminQuotaTests {

        @Test
        @DisplayName("uploadFile_withAdminRole_neverExceedsQuota")
        void uploadFile_withAdminRole_neverExceedsQuota() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            /** 模擬已用空間遠超過一般使用者配額 */
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(Long.MAX_VALUE / 2);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FileUploadResponse response = fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "ADMIN");

            assertThat(response).isNotNull();
        }
    }

    /** uploadFile：未知角色 → fallback USER 配額 */
    @Nested
    @DisplayName("uploadFile 未知角色 fallback 配額測試")
    class UploadFileUnknownRoleQuotaTests {

        @Test
        @DisplayName("uploadFile_withUnknownRole_fallsBackToUserQuota")
        void uploadFile_withUnknownRole_fallsBackToUserQuota() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            /** 剩餘空間充足（USER 配額 10MB，已用 0） */
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            /** GUEST 角色在 quotas map 不存在，應 fallback USER 配額（10MB） */
            FileUploadResponse response = fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "GUEST");

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("uploadFile_withUnknownRole_quotaExceeded_throwsException")
        void uploadFile_withUnknownRole_quotaExceeded_throwsException() {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            /** 已用空間等於 USER fallback 配額（10MB），再上傳會超限 */
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(10L * 1024 * 1024);

            assertThatThrownBy(() ->
                    fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "GUEST"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(FileErrorCode.QUOTA_EXCEEDED.getMessage());
        }
    }

    /** uploadFile：filename 無副檔名 → extractExtension 返回 "bin" */
    @Nested
    @DisplayName("uploadFile 無副檔名 extractExtension 測試")
    class UploadFileNoExtensionTests {

        @Test
        @DisplayName("uploadFile_withNoExtensionFilename_usesBinExtension")
        void uploadFile_withNoExtensionFilename_usesBinExtension() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            /** filename 無 dot */
            MockMultipartFile file = new MockMultipartFile("file", "testfile", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getStoragePath()).endsWith(".bin");
        }

        @Test
        @DisplayName("uploadFile_withNullFilenameForExtension_usesBinExtension")
        void uploadFile_withNullFilenameForExtension_usesBinExtension() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", null, "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getStoragePath()).endsWith(".bin");
        }
    }

    /** uploadFile：originalName 空白字串 → "unnamed" */
    @Nested
    @DisplayName("uploadFile 空白 filename 轉 unnamed 測試")
    class UploadFileBlankFilenameTests {

        @Test
        @DisplayName("uploadFile_withBlankFilename_usesDefaultName")
        void uploadFile_withBlankFilename_usesDefaultName() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "   ", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getOriginalName()).isEqualTo("unnamed");
        }
    }

    /** uploadFile：超長 filename 無 dot → 截斷前 255 字元 */
    @Nested
    @DisplayName("uploadFile 超長 filename 無 dot 截斷測試")
    class UploadFileLongFilenameNoDotTests {

        @Test
        @DisplayName("uploadFile_withLongFilenameNoDot_truncatesTo255")
        void uploadFile_withLongFilenameNoDot_truncatesTo255() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            String longName = "a".repeat(300); // 無副檔名
            MockMultipartFile file = new MockMultipartFile("file", longName, "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getOriginalName()).hasSize(255);
        }

        @Test
        @DisplayName("uploadFile_withLongFilenameVeryLongExt_truncatesTo255")
        void uploadFile_withLongFilenameVeryLongExt_truncatesTo255() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            /** ext 長度 260 chars，allowedBase <= 0 -> 直接截斷 255 */
            String longExt = "." + "e".repeat(260);
            String longName = "base" + longExt;
            MockMultipartFile file = new MockMultipartFile("file", longName, "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getOriginalName()).hasSizeLessThanOrEqualTo(255);
        }
    }

    /** deleteFile：fileId 不存在 → FILE_NOT_FOUND */
    @Nested
    @DisplayName("deleteFile 不存在檔案測試")
    class DeleteFileNotFoundTests {

        @Test
        @DisplayName("deleteFile_whenFileNotFound_throwsBusinessException")
        void deleteFile_whenFileNotFound_throwsBusinessException() {
            UUID fileId = UUID.randomUUID();
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> fileService.deleteFile(fileId, UUID.randomUUID(), false))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(FileErrorCode.FILE_NOT_FOUND.getMessage());
        }
    }

    /** deleteFile：isAdmin=true 可刪除他人檔案 */
    @Nested
    @DisplayName("deleteFile 管理員刪除他人檔案測試")
    class DeleteFileAdminTests {

        @Test
        @DisplayName("deleteFile_byAdmin_canDeleteOtherUserFile")
        void deleteFile_byAdmin_canDeleteOtherUserFile() throws Exception {
            UUID ownerId = UUID.randomUUID();
            UUID adminId = UUID.randomUUID(); // 不同使用者
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/test.jpg");
            metadata.setUploaderId(ownerId);
            metadata.setHasThumbnail(false);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));

            /** admin 可以刪除非自己的檔案 */
            fileService.deleteFile(fileId, adminId, true);

            verify(fileMetadataRepository).deleteById(fileId);
        }
    }

    /** deleteFile：有縮圖且 storagePath 無 dot → buildThumbPath 產生 _thumb 後綴，仍執行兩次 removeObject */
    @Nested
    @DisplayName("deleteFile 縮圖路徑無 dot 分支測試")
    class DeleteFileThumbNoDotTests {

        @Test
        @DisplayName("deleteFile_withThumbnailAndNoDotInPath_callsRemoveObjectTwice")
        void deleteFile_withThumbnailAndNoDotInPath_callsRemoveObjectTwice() throws Exception {
            UUID ownerId = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/uuidnodot"); // 無 dot
            metadata.setUploaderId(ownerId);
            metadata.setHasThumbnail(true);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));

            fileService.deleteFile(fileId, ownerId, false);

            /** buildThumbPath 無 dot 分支：storagePath + "_thumb"，仍應呼叫兩次 removeObject */
            verify(minioClient, times(2)).removeObject(any(RemoveObjectArgs.class));
        }
    }

    /** getFileMetadata：fileId 不存在 → FILE_NOT_FOUND */
    @Nested
    @DisplayName("getFileMetadata 不存在檔案測試")
    class GetFileMetadataNotFoundTests {

        @Test
        @DisplayName("getFileMetadata_whenFileNotFound_throwsBusinessException")
        void getFileMetadata_whenFileNotFound_throwsBusinessException() {
            UUID fileId = UUID.randomUUID();
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> fileService.getFileMetadata(fileId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(FileErrorCode.FILE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("getFileMetadata_whenFileExists_returnsMetadata")
        void getFileMetadata_whenFileExists_returnsMetadata() {
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/test.jpg");
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));

            FileMetadata result = fileService.getFileMetadata(fileId);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(fileId);
        }
    }

    /** getQuota：ADMIN 角色 → limit=Long.MAX_VALUE，remaining=Long.MAX_VALUE */
    @Nested
    @DisplayName("getQuota ADMIN 角色配額測試")
    class GetQuotaAdminTests {

        @Test
        @DisplayName("getQuota_forAdmin_returnsUnlimitedQuota")
        void getQuota_forAdmin_returnsUnlimitedQuota() {
            UUID adminId = UUID.randomUUID();
            when(fileMetadataRepository.sumSizeByUploaderId(adminId)).thenReturn(100L * 1024 * 1024);

            QuotaResponse quota = fileService.getQuota(adminId, "ADMIN");

            assertThat(quota.getLimitBytes()).isEqualTo(Long.MAX_VALUE);
            assertThat(quota.getRemainingBytes()).isEqualTo(Long.MAX_VALUE);
        }
    }

    /** getQuota：已用量超過配額 → remaining=0（不為負數） */
    @Nested
    @DisplayName("getQuota 配額用盡 remaining 為零測試")
    class GetQuotaOverusedTests {

        @Test
        @DisplayName("getQuota_whenUsedExceedsLimit_remainingIsZero")
        void getQuota_whenUsedExceedsLimit_remainingIsZero() {
            UUID userId = UUID.randomUUID();
            /** 已用量超過 10MB USER 配額 */
            long usedBytes = 12L * 1024 * 1024;
            when(fileMetadataRepository.sumSizeByUploaderId(userId)).thenReturn(usedBytes);

            QuotaResponse quota = fileService.getQuota(userId, "USER");

            assertThat(quota.getRemainingBytes()).isEqualTo(0L);
            assertThat(quota.getUsedBytes()).isEqualTo(usedBytes);
        }
    }

    /** uploadFile：DB 失敗時補償刪除 MinIO 也失敗（只 log，不再拋出額外異常） */
    @Nested
    @DisplayName("uploadFile 補償刪除 MinIO 也失敗測試")
    class CompensationMinioAlsoFailsTests {

        @Test
        @DisplayName("uploadFile_whenDbFailsAndCompensationAlsoFails_throwsOriginalDbException")
        void uploadFile_whenDbFailsAndCompensationAlsoFails_throwsOriginalDbException() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenThrow(new RuntimeException("DB failure"));
            /** 補償刪除 MinIO 也拋出異常 */
            doThrow(new RuntimeException("MinIO compensation failed"))
                    .when(minioClient).removeObject(any(RemoveObjectArgs.class));

            /** 應拋出原始 DB 例外（補償失敗只 log，不蓋過原始例外） */
            assertThatThrownBy(() ->
                    fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("DB failure");
        }
    }

    /** B2：uploadFile 新增 articleUuid 欄位後的 NULL 覆寫回歸檢查 */
    @Nested
    @DisplayName("uploadFile articleUuid 欄位回歸測試")
    class UploadFileArticleUuidRegressionTests {

        /**
         * 驗證新增 articleUuid 欄位後，uploadFile（INSERT 新檔案）儲存的 metadata 之 articleUuid 為 null。
         *
         * <p>本專案已知風險：Spring Data JDBC 對未設值欄位送顯式 NULL，可能誤清既有值。
         * 但 uploadFile 一律 {@code new FileMetadata()} 執行 INSERT（見 FileServiceImpl.uploadFile），
         * 不會讀取既有列再覆寫，因此「清空既有 articleUuid」的風險場景在此路徑不成立——
         * 新檔案本就應該是未綁定（null）狀態。本測試固定此事實，防止日後改動誤帶入非 null 預設值。</p>
         */
        @Test
        @DisplayName("uploadFile_afterAddingArticleUuidField_savesWithNullArticleUuid")
        void uploadFile_afterAddingArticleUuidField_savesWithNullArticleUuid() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getArticleUuid()).isNull();
        }
    }

    /** B2：canRead 授權矩陣測試（spec §4，每條規則含正例與反例） */
    @Nested
    @DisplayName("canRead 授權矩陣測試")
    class CanReadTests {

        private FileMetadata metadataWith(UUID fileId, UUID uploaderId, UsageType usageType, UUID articleUuid) {
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setUploaderId(uploaderId);
            metadata.setUsageType(usageType);
            metadata.setArticleUuid(articleUuid);
            return metadata;
        }

        /** 規則1：usageType=AVATAR，匿名（requesterId=null）→ 允許 */
        @Test
        @DisplayName("canRead_avatarUsageType_anonymousAllowed")
        void canRead_avatarUsageType_anonymousAllowed() {
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = metadataWith(fileId, UUID.randomUUID(), UsageType.AVATAR, null);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));

            assertThat(fileService.canRead(fileId, null, false)).isTrue();
        }

        /** 規則2：已綁定 + PUBLISHED，匿名 → 允許 */
        @Test
        @DisplayName("canRead_boundToPublishedArticle_anonymousAllowed")
        void canRead_boundToPublishedArticle_anonymousAllowed() {
            UUID fileId = UUID.randomUUID();
            UUID uploaderId = UUID.randomUUID();
            UUID articleUuid = UUID.randomUUID();
            FileMetadata metadata = metadataWith(fileId, uploaderId, UsageType.ARTICLE_CONTENT, articleUuid);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, 99L, "PUBLISHED", null, null)));

            assertThat(fileService.canRead(fileId, null, false)).isTrue();
        }

        /** 規則2 反例：已綁定 + DRAFT，匿名 → 拒絕 */
        @Test
        @DisplayName("canRead_boundToDraftArticle_anonymousDenied")
        void canRead_boundToDraftArticle_anonymousDenied() {
            UUID fileId = UUID.randomUUID();
            UUID uploaderId = UUID.randomUUID();
            UUID articleUuid = UUID.randomUUID();
            FileMetadata metadata = metadataWith(fileId, uploaderId, UsageType.ARTICLE_CONTENT, articleUuid);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, 99L, "DRAFT", null, null)));

            assertThat(fileService.canRead(fileId, null, false)).isFalse();
        }

        /** 已綁定 + DRAFT，非上傳者的一般使用者 → 拒絕 */
        @Test
        @DisplayName("canRead_boundToDraftArticle_otherUserDenied")
        void canRead_boundToDraftArticle_otherUserDenied() {
            UUID fileId = UUID.randomUUID();
            UUID uploaderId = UUID.randomUUID();
            UUID otherUserId = UUID.randomUUID();
            UUID articleUuid = UUID.randomUUID();
            FileMetadata metadata = metadataWith(fileId, uploaderId, UsageType.ARTICLE_CONTENT, articleUuid);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, 99L, "DRAFT", null, null)));

            assertThat(fileService.canRead(fileId, otherUserId, false)).isFalse();
        }

        /** 已綁定 + DRAFT，上傳者本人 → 允許 */
        @Test
        @DisplayName("canRead_boundToDraftArticle_uploaderAllowed")
        void canRead_boundToDraftArticle_uploaderAllowed() {
            UUID fileId = UUID.randomUUID();
            UUID uploaderId = UUID.randomUUID();
            UUID articleUuid = UUID.randomUUID();
            FileMetadata metadata = metadataWith(fileId, uploaderId, UsageType.ARTICLE_CONTENT, articleUuid);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, 99L, "DRAFT", null, null)));

            assertThat(fileService.canRead(fileId, uploaderId, false)).isTrue();
        }

        /** 已綁定 + DRAFT，ADMIN → 允許 */
        @Test
        @DisplayName("canRead_boundToDraftArticle_adminAllowed")
        void canRead_boundToDraftArticle_adminAllowed() {
            UUID fileId = UUID.randomUUID();
            UUID uploaderId = UUID.randomUUID();
            UUID adminId = UUID.randomUUID();
            UUID articleUuid = UUID.randomUUID();
            FileMetadata metadata = metadataWith(fileId, uploaderId, UsageType.ARTICLE_CONTENT, articleUuid);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, 99L, "DRAFT", null, null)));

            assertThat(fileService.canRead(fileId, adminId, true)).isTrue();
        }

        /** 關鍵 fail-safe：未綁定任何文章，匿名 → 拒絕 */
        @Test
        @DisplayName("canRead_unbound_anonymousDenied")
        void canRead_unbound_anonymousDenied() {
            UUID fileId = UUID.randomUUID();
            UUID uploaderId = UUID.randomUUID();
            FileMetadata metadata = metadataWith(fileId, uploaderId, UsageType.ARTICLE_CONTENT, null);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));

            assertThat(fileService.canRead(fileId, null, false)).isFalse();
        }

        /** 未綁定，上傳者本人 → 允許 */
        @Test
        @DisplayName("canRead_unbound_uploaderAllowed")
        void canRead_unbound_uploaderAllowed() {
            UUID fileId = UUID.randomUUID();
            UUID uploaderId = UUID.randomUUID();
            FileMetadata metadata = metadataWith(fileId, uploaderId, UsageType.ARTICLE_CONTENT, null);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));

            assertThat(fileService.canRead(fileId, uploaderId, false)).isTrue();
        }

        /** 未綁定，ADMIN → 允許（規則4與綁定狀態無關） */
        @Test
        @DisplayName("canRead_unbound_adminAllowed")
        void canRead_unbound_adminAllowed() {
            UUID fileId = UUID.randomUUID();
            UUID uploaderId = UUID.randomUUID();
            UUID adminId = UUID.randomUUID();
            FileMetadata metadata = metadataWith(fileId, uploaderId, UsageType.ARTICLE_CONTENT, null);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));

            assertThat(fileService.canRead(fileId, adminId, true)).isTrue();
        }

        /** 檔案不存在 → 依既有慣例（同 getFileMetadata / deleteFile）拋 BusinessException(FILE_NOT_FOUND) */
        @Test
        @DisplayName("canRead_fileNotFound_throwsBusinessException")
        void canRead_fileNotFound_throwsBusinessException() {
            UUID fileId = UUID.randomUUID();
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> fileService.canRead(fileId, UUID.randomUUID(), false))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(FileErrorCode.FILE_NOT_FOUND.getMessage());
        }
    }

    /**
     * B2：bindToArticle 測試——完整替換語意（非附加）+ 擁有權不變量
     *
     * <p>
     * 安全複審 CRITICAL 修復：一個檔案只能被綁定到「該檔案的上傳者 == 該文章的作者」的文章上。
     * {@link #stubArticleAuthor} 統一建立「文章 articleUuid 的作者 UUID 為 authorUuid」的
     * {@code articleFacade}/{@code userFacade} mock 鏈。
     * </p>
     */
    @Nested
    @DisplayName("bindToArticle 測試")
    class BindToArticleTests {

        private static final Long AUTHOR_INTERNAL_ID = 100L;

        /** 建立「文章 articleUuid 的作者為 authorUuid」的 articleFacade + userFacade mock 鏈 */
        private void stubArticleAuthor(UUID articleUuid, UUID authorUuid) {
            when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, AUTHOR_INTERNAL_ID, "DRAFT", null, null)));
            when(userFacade.getUserUuidById(AUTHOR_INTERNAL_ID)).thenReturn(Optional.of(authorUuid));
        }

        /** 新綁定：清單內檔案皆設定 articleUuid（合法情境：檔案 uploader == 文章作者） */
        @Test
        @DisplayName("bindToArticle_withNewFiles_bindsAllToArticle")
        void bindToArticle_withNewFiles_bindsAllToArticle() {
            UUID articleUuid = UUID.randomUUID();
            UUID authorUuid = UUID.randomUUID();
            UUID file1 = UUID.randomUUID();
            UUID file2 = UUID.randomUUID();
            FileMetadata metadata1 = new FileMetadata();
            metadata1.setId(file1);
            metadata1.setUploaderId(authorUuid);
            FileMetadata metadata2 = new FileMetadata();
            metadata2.setId(file2);
            metadata2.setUploaderId(authorUuid);
            stubArticleAuthor(articleUuid, authorUuid);
            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of());
            when(fileMetadataRepository.findById(file1)).thenReturn(Optional.of(metadata1));
            when(fileMetadataRepository.findById(file2)).thenReturn(Optional.of(metadata2));

            fileService.bindToArticle(articleUuid, List.of(file1, file2));

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository, times(2)).save(captor.capture());
            assertThat(captor.getAllValues())
                    .allSatisfy(m -> assertThat(m.getArticleUuid()).isEqualTo(articleUuid));
        }

        /** 關鍵：重新綁定時，舊清單中不在新清單內的檔案要解除綁定（設回 null），避免權限殘留 */
        @Test
        @DisplayName("bindToArticle_whenRebinding_unbindsFilesNotInNewList")
        void bindToArticle_whenRebinding_unbindsFilesNotInNewList() {
            UUID articleUuid = UUID.randomUUID();
            UUID authorUuid = UUID.randomUUID();
            UUID keepFile = UUID.randomUUID();
            UUID removedFile = UUID.randomUUID();

            FileMetadata keepMetadata = new FileMetadata();
            keepMetadata.setId(keepFile);
            keepMetadata.setArticleUuid(articleUuid);
            keepMetadata.setUploaderId(authorUuid);

            FileMetadata removedMetadata = new FileMetadata();
            removedMetadata.setId(removedFile);
            removedMetadata.setArticleUuid(articleUuid);
            removedMetadata.setUploaderId(authorUuid);

            /**
             * 補上作者解析 mock：雖然 keepFile 已綁定於本文章，第二迴圈對它是 no-op
             * （{@code articleUuid.equals(metadata.getArticleUuid())} 提前為 true），本不會真正
             * 觸發擁有權比對；但補齊 mock 讓此測試不依賴「作者解析失敗而整段跳過」的巧合，
             * 明確驗證新不變量之下，合法的重新綁定情境仍正常運作。
             */
            stubArticleAuthor(articleUuid, authorUuid);
            /** 目前已綁定此文章的檔案：keepFile、removedFile */
            when(fileMetadataRepository.findByArticleUuid(articleUuid))
                    .thenReturn(List.of(keepMetadata, removedMetadata));
            when(fileMetadataRepository.findById(keepFile)).thenReturn(Optional.of(keepMetadata));

            /** 新清單只剩 keepFile，removedFile 應被解除綁定 */
            fileService.bindToArticle(articleUuid, List.of(keepFile));

            assertThat(removedMetadata.getArticleUuid()).isNull();
            verify(fileMetadataRepository).save(removedMetadata);
            assertThat(keepMetadata.getArticleUuid()).isEqualTo(articleUuid);
        }

        /** 空清單：解除該文章的所有既有綁定 */
        @Test
        @DisplayName("bindToArticle_withEmptyList_unbindsAllExistingBindings")
        void bindToArticle_withEmptyList_unbindsAllExistingBindings() {
            UUID articleUuid = UUID.randomUUID();
            UUID file1 = UUID.randomUUID();
            UUID file2 = UUID.randomUUID();

            FileMetadata metadata1 = new FileMetadata();
            metadata1.setId(file1);
            metadata1.setArticleUuid(articleUuid);
            FileMetadata metadata2 = new FileMetadata();
            metadata2.setId(file2);
            metadata2.setArticleUuid(articleUuid);

            when(fileMetadataRepository.findByArticleUuid(articleUuid))
                    .thenReturn(List.of(metadata1, metadata2));

            fileService.bindToArticle(articleUuid, List.of());

            assertThat(metadata1.getArticleUuid()).isNull();
            assertThat(metadata2.getArticleUuid()).isNull();
            verify(fileMetadataRepository, times(2)).save(any(FileMetadata.class));
        }

        /** 傳 null 清單：等同空清單，解除所有既有綁定，不拋例外 */
        @Test
        @DisplayName("bindToArticle_withNullList_treatsAsEmptyAndUnbindsAll")
        void bindToArticle_withNullList_treatsAsEmptyAndUnbindsAll() {
            UUID articleUuid = UUID.randomUUID();
            UUID file1 = UUID.randomUUID();
            FileMetadata metadata1 = new FileMetadata();
            metadata1.setId(file1);
            metadata1.setArticleUuid(articleUuid);

            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of(metadata1));

            fileService.bindToArticle(articleUuid, null);

            assertThat(metadata1.getArticleUuid()).isNull();
        }

        /** 清單內含不存在的 fileUuid → 安靜略過，不拋例外（內文可能含壞連結，不可讓綁定失敗） */
        @Test
        @DisplayName("bindToArticle_withNonExistentFileUuid_skipsQuietly")
        void bindToArticle_withNonExistentFileUuid_skipsQuietly() {
            UUID articleUuid = UUID.randomUUID();
            UUID authorUuid = UUID.randomUUID();
            UUID nonExistentFile = UUID.randomUUID();
            /** 補上作者解析 mock，確保本測試真正走到 findById 回傳 empty 的 ifPresent no-op 路徑，
             *  而非因作者解析失敗而在進入迴圈前就整段跳過。 */
            stubArticleAuthor(articleUuid, authorUuid);
            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of());
            when(fileMetadataRepository.findById(nonExistentFile)).thenReturn(Optional.empty());

            assertThatCode(() -> fileService.bindToArticle(articleUuid, List.of(nonExistentFile)))
                    .doesNotThrowAnyException();

            verify(fileMetadataRepository, never()).save(any(FileMetadata.class));
        }

        /**
         * CRITICAL 修復驗收 1（他人檔案不可被綁定）：
         * 檔案 A 的 uploader 是 userX，文章 B 的作者是 userY → bindToArticle(B, [A]) 後，
         * A 的 articleUuid 維持不變（未被改綁）。
         */
        @Test
        @DisplayName("bindToArticle_whenFileOwnedByDifferentUser_doesNotBindAndLeavesArticleUuidUnchanged")
        void bindToArticle_whenFileOwnedByDifferentUser_doesNotBindAndLeavesArticleUuidUnchanged() {
            UUID articleB = UUID.randomUUID();
            UUID userX = UUID.randomUUID();
            UUID userY = UUID.randomUUID();
            UUID fileA = UUID.randomUUID();

            FileMetadata metadataA = new FileMetadata();
            metadataA.setId(fileA);
            metadataA.setUploaderId(userX);
            metadataA.setArticleUuid(null);

            stubArticleAuthor(articleB, userY);
            when(fileMetadataRepository.findByArticleUuid(articleB)).thenReturn(List.of());
            when(fileMetadataRepository.findById(fileA)).thenReturn(Optional.of(metadataA));

            fileService.bindToArticle(articleB, List.of(fileA));

            assertThat(metadataA.getArticleUuid()).isNull();
            verify(fileMetadataRepository, never()).save(any(FileMetadata.class));
        }

        /**
         * CRITICAL 修復驗收 2（不可竊取他人已發布文章的圖）：
         * 檔案 A 已綁定 userX 的文章 P，userY 的文章 Q 嘗試綁定 A → A 仍綁在 P。
         */
        @Test
        @DisplayName("bindToArticle_whenAttackerTriesToStealAnotherArticlesFile_fileStaysWithOriginalArticle")
        void bindToArticle_whenAttackerTriesToStealAnotherArticlesFile_fileStaysWithOriginalArticle() {
            UUID articleP = UUID.randomUUID();
            UUID articleQ = UUID.randomUUID();
            UUID userX = UUID.randomUUID();
            UUID userY = UUID.randomUUID();
            UUID fileA = UUID.randomUUID();

            FileMetadata metadataA = new FileMetadata();
            metadataA.setId(fileA);
            metadataA.setUploaderId(userX);
            metadataA.setArticleUuid(articleP);

            stubArticleAuthor(articleQ, userY);
            when(fileMetadataRepository.findByArticleUuid(articleQ)).thenReturn(List.of());
            when(fileMetadataRepository.findById(fileA)).thenReturn(Optional.of(metadataA));

            fileService.bindToArticle(articleQ, List.of(fileA));

            assertThat(metadataA.getArticleUuid()).isEqualTo(articleP);
            verify(fileMetadataRepository, never()).save(any(FileMetadata.class));
        }

        /** fail-safe：文章不存在 → 不綁定任何檔案、不拋錯 */
        @Test
        @DisplayName("bindToArticle_whenArticleNotFound_bindsNothingAndDoesNotThrow")
        void bindToArticle_whenArticleNotFound_bindsNothingAndDoesNotThrow() {
            UUID articleUuid = UUID.randomUUID();
            UUID fileA = UUID.randomUUID();
            FileMetadata metadataA = new FileMetadata();
            metadataA.setId(fileA);
            metadataA.setUploaderId(UUID.randomUUID());

            when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());
            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of());
            when(fileMetadataRepository.findById(fileA)).thenReturn(Optional.of(metadataA));

            assertThatCode(() -> fileService.bindToArticle(articleUuid, List.of(fileA)))
                    .doesNotThrowAnyException();

            assertThat(metadataA.getArticleUuid()).isNull();
            verify(fileMetadataRepository, never()).save(any(FileMetadata.class));
        }

        /** fail-safe：文章存在但作者 UUID 無法解析（userFacade 查無）→ 不綁定任何檔案、不拋錯 */
        @Test
        @DisplayName("bindToArticle_whenAuthorUuidNotResolvable_bindsNothingAndDoesNotThrow")
        void bindToArticle_whenAuthorUuidNotResolvable_bindsNothingAndDoesNotThrow() {
            UUID articleUuid = UUID.randomUUID();
            UUID fileA = UUID.randomUUID();
            FileMetadata metadataA = new FileMetadata();
            metadataA.setId(fileA);
            metadataA.setUploaderId(UUID.randomUUID());

            when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, AUTHOR_INTERNAL_ID, "DRAFT", null, null)));
            when(userFacade.getUserUuidById(AUTHOR_INTERNAL_ID)).thenReturn(Optional.empty());
            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of());
            when(fileMetadataRepository.findById(fileA)).thenReturn(Optional.of(metadataA));

            assertThatCode(() -> fileService.bindToArticle(articleUuid, List.of(fileA)))
                    .doesNotThrowAnyException();

            assertThat(metadataA.getArticleUuid()).isNull();
            verify(fileMetadataRepository, never()).save(any(FileMetadata.class));
        }
    }

    /** B4：uploadFile 回傳相對路徑 URL（spec §3.1），不再回傳 MinIO 直連網址 */
    @Nested
    @DisplayName("uploadFile 相對路徑 URL 測試 (B4)")
    class UploadFileRelativeUrlTests {

        /**
         * 驗證 uploadFile 回傳的 url 為相對路徑 {@code /api/v1/files/{id}/content}，
         * 不再帶 MinIO endpoint host，避免換域名時內文全破（spec §3.1）。
         * Red：目前實作組 {@code minioEndpoint + "/" + bucketName + "/" + storagePath}，此測試會失敗。
         */
        @Test
        @DisplayName("uploadFile_returnsRelativePathUrl_notAbsoluteMinioUrl")
        void uploadFile_returnsRelativePathUrl_notAbsoluteMinioUrl() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FileUploadResponse response = fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            assertThat(response.getUrl()).startsWith("/api/v1/files/");
            assertThat(response.getUrl()).endsWith("/content");
            assertThat(response.getUrl()).doesNotContain("http");
            assertThat(response.getUrl()).contains(response.getId().toString());
        }
    }

    /**
     * B4：uploadFile 可選 articleUuid 參數 —— 有值即直接綁定；
     * 刻意不透過 bindToArticle（完整替換語意），避免單檔上傳誤解除同文章其他既有綁定檔案。
     */
    @Nested
    @DisplayName("uploadFile articleUuid 綁定測試 (B4)")
    class UploadFileArticleUuidBindingTests {

        /**
         * 合法情境：uploaderId == 該文章作者 UUID → 正常直接綁定（MEDIUM 1 修復後仍需維持既有行為）。
         */
        @Test
        @DisplayName("uploadFile_withArticleUuid_setsArticleUuidDirectlyWithoutTouchingOtherFiles")
        void uploadFile_withArticleUuid_setsArticleUuidDirectlyWithoutTouchingOtherFiles() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            UUID articleUuid = UUID.randomUUID();
            UUID uploaderId = UUID.randomUUID();
            Long authorInternalId = 200L;
            when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, authorInternalId, "DRAFT", null, null)));
            when(userFacade.getUserUuidById(authorInternalId)).thenReturn(Optional.of(uploaderId));
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, uploaderId, "AUTHOR", articleUuid);

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getArticleUuid()).isEqualTo(articleUuid);
            /**
             * 關鍵防呆：bindToArticle 的「完整替換」語意會先呼叫 findByArticleUuid 找出
             * 該文章目前已綁定、但不在新清單內的舊檔案並解除綁定。單檔上傳綁定若誤用
             * bindToArticle(articleUuid, List.of(newFileId))，會把同文章所有既有檔案一併解除綁定。
             * 因此本測試斷言 findByArticleUuid 完全不被呼叫，確保走的是「直接 set」而非 bindToArticle。
             */
            verify(fileMetadataRepository, never()).findByArticleUuid(any());
        }

        /**
         * MEDIUM 1 修復驗收：articleUuid 對應的文章作者不是本次上傳者 → 忽略該參數，
         * 存成 null（未綁定），不拋錯。
         */
        @Test
        @DisplayName("uploadFile_withArticleUuidNotOwnedByRequester_savesWithNullArticleUuid")
        void uploadFile_withArticleUuidNotOwnedByRequester_savesWithNullArticleUuid() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            UUID articleUuid = UUID.randomUUID();
            UUID uploaderId = UUID.randomUUID();
            UUID actualAuthorUuid = UUID.randomUUID();
            Long authorInternalId = 201L;
            when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, authorInternalId, "PUBLISHED", null, null)));
            when(userFacade.getUserUuidById(authorInternalId)).thenReturn(Optional.of(actualAuthorUuid));
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, uploaderId, "AUTHOR", articleUuid);

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getArticleUuid()).isNull();
        }

        /**
         * articleUuid 參數為 null（未指定綁定）→ 直接存 null，不涉及擁有權解析。
         */
        @Test
        @DisplayName("uploadFile_withNullArticleUuidParam_savesWithNullArticleUuid")
        void uploadFile_withNullArticleUuidParam_savesWithNullArticleUuid() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR", null);

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getArticleUuid()).isNull();
        }
    }

    /** B4：generatePresignedUrl —— 產生短效簽名網址供 /content 端點 302 導向使用 */
    @Nested
    @DisplayName("generatePresignedUrl 測試 (B4)")
    class GeneratePresignedUrlTests {

        /**
         * 驗證使用 MinIO SDK getPresignedObjectUrl 以 GET 方法、5 分鐘（300 秒）效期產生簽名網址。
         * Red：FileService 尚無此方法，編譯失敗。
         */
        @Test
        @DisplayName("generatePresignedUrl_whenFileExists_callsMinioWithGetMethodAndFiveMinuteExpiry")
        void generatePresignedUrl_whenFileExists_callsMinioWithGetMethodAndFiveMinuteExpiry() throws Exception {
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/test.jpg");
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                    .thenReturn("http://localhost:9000/test-bucket/articles/2024/01/01/test.jpg?X-Amz-Signature=abc");

            String url = fileService.generatePresignedUrl(fileId);

            assertThat(url).contains("X-Amz-Signature");
            ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
            verify(minioClient).getPresignedObjectUrl(captor.capture());
            GetPresignedObjectUrlArgs args = captor.getValue();
            assertThat(args.method()).isEqualTo(Method.GET);
            assertThat(args.bucket()).isEqualTo("test-bucket");
            assertThat(args.object()).isEqualTo("articles/2024/01/01/test.jpg");
            assertThat(args.expiry()).isEqualTo(300);
        }

        /**
         * 檔案不存在時應拋出 BusinessException(FILE_NOT_FOUND)，讓 controller 能區分 404 vs 403。
         * Red：方法不存在，編譯失敗。
         */
        @Test
        @DisplayName("generatePresignedUrl_whenFileNotFound_throwsBusinessException")
        void generatePresignedUrl_whenFileNotFound_throwsBusinessException() {
            UUID fileId = UUID.randomUUID();
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> fileService.generatePresignedUrl(fileId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(FileErrorCode.FILE_NOT_FOUND.getMessage());
        }
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
