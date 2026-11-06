package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.SystemException;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.file.config.FileProperties;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.FileErrorCode;
import dowob.xyz.blog.module.file.event.ImageUploadedEvent;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.model.dto.FileUploadResponse;
import dowob.xyz.blog.module.file.model.dto.QuotaResponse;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.io.ByteArrayInputStream;
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
    private ArticleLookupFacade articleLookupFacade;

    private final FileProperties fileProperties = new FileProperties(
            Map.of("USER", DataSize.ofMegabytes(10), "AUTHOR", DataSize.ofMegabytes(500)),
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif")
    );

    @InjectMocks
    private FileServiceImpl fileService;

    /** 目前是否位於（mock）交易 callback 之內，用來驗證 MinIO 呼叫發生在 commit 之後 */
    private boolean inTransaction;

    /** 紀錄「交易內／交易外」的 removeObject 呼叫次數 */
    private int removeObjectCallsInsideTx;

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
            inTransaction = true;
            try {
                consumer.accept(null);
            } finally {
                inTransaction = false;
            }
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        try {
            doAnswer(inv -> {
                if (inTransaction) {
                    removeObjectCallsInsideTx++;
                }
                return null;
            }).when(minioClient).removeObject(any(RemoveObjectArgs.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

        /** 驗證上傳成功時發出的 ImageUploadedEvent 帶有發送端產生的 eventId（consumer 冪等的前提）且每次不同 */
        @Test
        @DisplayName("uploadFile_withValidImage_publishesImageUploadedEventWithFreshEventId")
        void uploadFile_withValidImage_publishesImageUploadedEventWithFreshEventId() throws Exception {
            byte[] jpegBytes = new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
                0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xD9
            };
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UUID uploaderId = UUID.randomUUID();

            FileUploadResponse first = fileService.uploadFile(
                    new MockMultipartFile("file", "a.jpg", "image/jpeg", jpegBytes),
                    UsageType.ARTICLE_CONTENT, uploaderId, "AUTHOR");
            fileService.uploadFile(
                    new MockMultipartFile("file", "b.jpg", "image/jpeg", jpegBytes),
                    UsageType.ARTICLE_CONTENT, uploaderId, "AUTHOR");

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(rabbitTemplate, times(2)).convertAndSend(any(String.class), any(String.class), captor.capture());
            List<ImageUploadedEvent> events = captor.getAllValues().stream()
                    .map(ImageUploadedEvent.class::cast).toList();
            assertThat(events).allSatisfy(e -> assertThat(e.eventId()).isNotNull());
            assertThat(events.get(0).eventId()).isNotEqualTo(events.get(1).eventId());
            assertThat(events.get(0).fileId()).isEqualTo(first.getId());
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

        /** RACE-16（修正版）：MinIO 刪除失敗 → 拋 STORAGE_ERROR，且 DB 列沒有被刪（交由呼叫端／MQ 重試） */
        @Test
        @DisplayName("deleteFile_whenMinioFails_throwsAndDbRowNotDeleted")
        void deleteFile_whenMinioFails_throwsAndDbRowNotDeleted() throws Exception {
            UUID ownerId = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/test.jpg");
            metadata.setUploaderId(ownerId);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            doThrow(new RuntimeException("MinIO error")).when(minioClient).removeObject(any(RemoveObjectArgs.class));

            assertThatThrownBy(() -> fileService.deleteFile(fileId, ownerId, false))
                    .isInstanceOf(SystemException.class)
                    .hasMessageContaining(CommonErrorCode.STORAGE_ERROR.getMessage());

            verify(fileMetadataRepository, never()).deleteById(any());
        }

        /** RACE-16（修正版）：MinIO 刪除（原檔 + 縮圖）在 DB 交易之外，且先於 DB 刪除 */
        @Test
        @DisplayName("deleteFile_removesMinioObjectsOutsideAndBeforeDbTransaction")
        void deleteFile_removesMinioObjectsOutsideAndBeforeDbTransaction() throws Exception {
            UUID ownerId = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/test.jpg");
            metadata.setUploaderId(ownerId);
            metadata.setHasThumbnail(true);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            java.util.concurrent.atomic.AtomicBoolean dbDeleteInsideTx = new java.util.concurrent.atomic.AtomicBoolean();
            doAnswer(inv -> {
                dbDeleteInsideTx.set(inTransaction);
                return null;
            }).when(fileMetadataRepository).deleteById(fileId);

            fileService.deleteFile(fileId, ownerId, false);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(minioClient, fileMetadataRepository);
            order.verify(minioClient, times(2)).removeObject(any(RemoveObjectArgs.class));
            order.verify(fileMetadataRepository).deleteById(fileId);
            assertThat(removeObjectCallsInsideTx).as("MinIO 呼叫不得在交易內").isZero();
            assertThat(dbDeleteInsideTx).as("DB 刪除在短交易內").isTrue();
        }

        /** 已知代價：MinIO 已刪、DB 刪除失敗 → 拋錯；重試時 MinIO 端為 no-op，DB 列被刪掉 */
        @Test
        @DisplayName("deleteFile_whenDbDeleteFailsAfterMinio_retrySucceedsAndDeletesRow")
        void deleteFile_whenDbDeleteFailsAfterMinio_retrySucceedsAndDeletesRow() throws Exception {
            UUID ownerId = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/test.jpg");
            metadata.setUploaderId(ownerId);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));
            doThrow(new RuntimeException("db down")).doNothing().when(fileMetadataRepository).deleteById(fileId);

            assertThatThrownBy(() -> fileService.deleteFile(fileId, ownerId, false))
                    .hasMessageContaining("db down");
            verify(minioClient, times(2)).removeObject(any(RemoveObjectArgs.class));

            assertThatCode(() -> fileService.deleteFile(fileId, ownerId, false)).doesNotThrowAnyException();
            verify(fileMetadataRepository, times(2)).deleteById(fileId);
            verify(minioClient, times(4)).removeObject(any(RemoveObjectArgs.class));
        }

        /** 授權失敗時不得碰 MinIO 或 DB 刪除 */
        @Test
        @DisplayName("deleteFile_whenAccessDenied_touchesNeitherMinioNorDb")
        void deleteFile_whenAccessDenied_touchesNeitherMinioNorDb() throws Exception {
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/test.jpg");
            metadata.setUploaderId(UUID.randomUUID());
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));

            assertThatThrownBy(() -> fileService.deleteFile(fileId, UUID.randomUUID(), false))
                    .isInstanceOf(BusinessException.class);

            verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
            verify(fileMetadataRepository, never()).deleteById(any());
        }

        /** RACE-16：deleteFile 不得再標註 @Transactional（否則外層代理仍會讓交易橫跨 MinIO 呼叫） */
        @Test
        @DisplayName("deleteFile_isNotAnnotatedTransactional")
        void deleteFile_isNotAnnotatedTransactional() throws Exception {
            java.lang.reflect.Method m = FileServiceImpl.class.getMethod(
                    "deleteFile", UUID.class, UUID.class, boolean.class);
            assertThat(m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
            assertThat(FileServiceImpl.class.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        }

        /** DATA-14：即使 hasThumbnail=false 也一律嘗試刪縮圖路徑（縮圖可能剛好在刪除競態中寫入） */
        @Test
        @DisplayName("deleteFile_withoutThumbnailFlag_stillAttemptsThumbnailRemoval")
        void deleteFile_withoutThumbnailFlag_stillAttemptsThumbnailRemoval() throws Exception {
            UUID ownerId = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            FileMetadata metadata = new FileMetadata();
            metadata.setId(fileId);
            metadata.setStoragePath("articles/2024/01/01/uuid.jpg");
            metadata.setUploaderId(ownerId);
            metadata.setHasThumbnail(false);
            when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(metadata));

            fileService.deleteFile(fileId, ownerId, false);

            ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
            verify(minioClient, times(2)).removeObject(captor.capture());
            assertThat(captor.getAllValues()).extracting(RemoveObjectArgs::object)
                    .containsExactly("articles/2024/01/01/uuid.jpg", "articles/2024/01/01/uuid_thumb.jpg");
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
            when(brokenTika.detect(any(InputStream.class)))
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

    /** uploadFile：filename 無副檔名 → 副檔名取自偵測出的 MIME（FILE-03） */
    @Nested
    @DisplayName("uploadFile 無副檔名 extractExtension 測試")
    class UploadFileNoExtensionTests {

        @Test
        @DisplayName("uploadFile_withNoExtensionFilename_usesDetectedMimeExtension")
        void uploadFile_withNoExtensionFilename_usesDetectedMimeExtension() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            /** filename 無 dot */
            MockMultipartFile file = new MockMultipartFile("file", "testfile", "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getStoragePath()).endsWith(".jpg");
        }

        @Test
        @DisplayName("uploadFile_withNullFilenameForExtension_usesDetectedMimeExtension")
        void uploadFile_withNullFilenameForExtension_usesDetectedMimeExtension() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", null, "image/jpeg", jpegBytes);
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getStoragePath()).endsWith(".jpg");
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
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "PUBLISHED", null, null, null)));

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
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "DRAFT", null, null, null)));

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
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "DRAFT", null, null, null)));

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
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "DRAFT", null, null, null)));

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
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "DRAFT", null, null, null)));

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
     * {@code articleLookupFacade} mock（作者 UUID 直接取自 {@code ArticleData.authorUuid}，不再經 userFacade 轉換）。
     * </p>
     */
    @Nested
    @DisplayName("bindToArticle 測試")
    class BindToArticleTests {

        private static final Long AUTHOR_INTERNAL_ID = 100L;

        /** 建立「文章 articleUuid 的作者為 authorUuid」的 articleLookupFacade mock */
        private void stubArticleAuthor(UUID articleUuid, UUID authorUuid) {
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "DRAFT", null, authorUuid, null)));
        }

            /** 以 findAllById 回傳指定的 metadata（PERF-23：批次讀取取代逐檔 findById） */
        private void stubFindAll(FileMetadata... found) {
            when(fileMetadataRepository.findAllById(any())).thenReturn(List.of(found));
        }

        /** 斷言 bindFilesToArticle 恰好以這組檔案 id 被呼叫一次 */
        private void verifyBoundExactly(UUID articleUuid, UUID... ids) {
            ArgumentCaptor<java.util.Collection<UUID>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(fileMetadataRepository).bindFilesToArticle(org.mockito.ArgumentMatchers.eq(articleUuid), captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(ids);
        }

        /** 斷言 unbindFilesFromArticle 恰好以這組檔案 id 被呼叫一次 */
        private void verifyUnboundExactly(UUID articleUuid, UUID... ids) {
            ArgumentCaptor<java.util.Collection<UUID>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(fileMetadataRepository).unbindFilesFromArticle(org.mockito.ArgumentMatchers.eq(articleUuid), captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(ids);
        }

        /** 沒有任何寫入（綁定、解除、整列 save）發生 */
        private void verifyNoWrites() {
            verify(fileMetadataRepository, never()).bindFilesToArticle(any(), any());
            verify(fileMetadataRepository, never()).unbindFilesFromArticle(any(), any());
            verify(fileMetadataRepository, never()).save(any(FileMetadata.class));
            verify(fileMetadataRepository, never()).saveAll(any());
        }

        private FileMetadata fileOf(UUID id, UUID uploader, UUID boundArticle) {
            FileMetadata m = new FileMetadata();
            m.setId(id);
            m.setUploaderId(uploader);
            m.setArticleUuid(boundArticle);
            return m;
        }

        /** 新綁定：清單內檔案皆綁定（合法情境：檔案 uploader == 文章作者） */
        @Test
        @DisplayName("bindToArticle_withNewFiles_bindsAllToArticle")
        void bindToArticle_withNewFiles_bindsAllToArticle() {
            UUID articleUuid = UUID.randomUUID();
            UUID authorUuid = UUID.randomUUID();
            UUID file1 = UUID.randomUUID();
            UUID file2 = UUID.randomUUID();
            stubArticleAuthor(articleUuid, authorUuid);
            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of());
            stubFindAll(fileOf(file1, authorUuid, null), fileOf(file2, authorUuid, null));

            fileService.bindToArticle(articleUuid, List.of(file1, file2));

            verifyBoundExactly(articleUuid, file1, file2);
            verify(fileMetadataRepository, never()).unbindFilesFromArticle(any(), any());
        }

        /** PERF-23：N 個檔案不再是逐檔 findById + save（2N+1 次存取），而是固定次數的批次存取 */
        @Test
        @DisplayName("bindToArticle_withManyFiles_usesConstantNumberOfRepositoryCalls")
        void bindToArticle_withManyFiles_usesConstantNumberOfRepositoryCalls() {
            UUID articleUuid = UUID.randomUUID();
            UUID authorUuid = UUID.randomUUID();
            List<UUID> ids = new java.util.ArrayList<>();
            List<FileMetadata> found = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                found.add(fileOf(id, authorUuid, null));
            }
            stubArticleAuthor(articleUuid, authorUuid);
            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of());
            when(fileMetadataRepository.findAllById(any())).thenReturn(found);

            fileService.bindToArticle(articleUuid, ids);

            verify(fileMetadataRepository, times(1)).findAllById(any());
            verify(fileMetadataRepository, times(1)).bindFilesToArticle(any(), any());
            verify(fileMetadataRepository, never()).findById(any());
            verify(fileMetadataRepository, never()).save(any(FileMetadata.class));
            verify(fileMetadataRepository, never()).saveAll(any());
            verifyBoundExactly(articleUuid, ids.toArray(new UUID[0]));
        }

        /** 關鍵：重新綁定時，舊清單中不在新清單內的檔案要解除綁定（設回 null），避免權限殘留 */
        @Test
        @DisplayName("bindToArticle_whenRebinding_unbindsFilesNotInNewList")
        void bindToArticle_whenRebinding_unbindsFilesNotInNewList() {
            UUID articleUuid = UUID.randomUUID();
            UUID authorUuid = UUID.randomUUID();
            UUID keepFile = UUID.randomUUID();
            UUID removedFile = UUID.randomUUID();
            FileMetadata keepMetadata = fileOf(keepFile, authorUuid, articleUuid);
            FileMetadata removedMetadata = fileOf(removedFile, authorUuid, articleUuid);
            stubArticleAuthor(articleUuid, authorUuid);
            /** 目前已綁定此文章的檔案：keepFile、removedFile */
            when(fileMetadataRepository.findByArticleUuid(articleUuid))
                    .thenReturn(List.of(keepMetadata, removedMetadata));
            stubFindAll(keepMetadata);

            /** 新清單只剩 keepFile，removedFile 應被解除綁定；keepFile 已綁定於本文章，不重複寫入 */
            fileService.bindToArticle(articleUuid, List.of(keepFile));

            verifyUnboundExactly(articleUuid, removedFile);
            verify(fileMetadataRepository, never()).bindFilesToArticle(any(), any());
        }

        /** 空清單：解除該文章的所有既有綁定 */
        @Test
        @DisplayName("bindToArticle_withEmptyList_unbindsAllExistingBindings")
        void bindToArticle_withEmptyList_unbindsAllExistingBindings() {
            UUID articleUuid = UUID.randomUUID();
            UUID file1 = UUID.randomUUID();
            UUID file2 = UUID.randomUUID();
            when(fileMetadataRepository.findByArticleUuid(articleUuid))
                    .thenReturn(List.of(fileOf(file1, null, articleUuid), fileOf(file2, null, articleUuid)));

            fileService.bindToArticle(articleUuid, List.of());

            verifyUnboundExactly(articleUuid, file1, file2);
            verify(fileMetadataRepository, never()).bindFilesToArticle(any(), any());
        }

        /** 傳 null 清單：等同空清單，解除所有既有綁定，不拋例外 */
        @Test
        @DisplayName("bindToArticle_withNullList_treatsAsEmptyAndUnbindsAll")
        void bindToArticle_withNullList_treatsAsEmptyAndUnbindsAll() {
            UUID articleUuid = UUID.randomUUID();
            UUID file1 = UUID.randomUUID();
            when(fileMetadataRepository.findByArticleUuid(articleUuid))
                    .thenReturn(List.of(fileOf(file1, null, articleUuid)));

            fileService.bindToArticle(articleUuid, null);

            verifyUnboundExactly(articleUuid, file1);
        }

        /** 清單內含不存在的 fileUuid → 安靜略過，不拋例外（內文可能含壞連結，不可讓綁定失敗） */
        @Test
        @DisplayName("bindToArticle_withNonExistentFileUuid_skipsQuietly")
        void bindToArticle_withNonExistentFileUuid_skipsQuietly() {
            UUID articleUuid = UUID.randomUUID();
            UUID authorUuid = UUID.randomUUID();
            UUID nonExistentFile = UUID.randomUUID();
            stubArticleAuthor(articleUuid, authorUuid);
            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of());
            stubFindAll();

            assertThatCode(() -> fileService.bindToArticle(articleUuid, List.of(nonExistentFile)))
                    .doesNotThrowAnyException();

            verifyNoWrites();
        }

        /**
         * CRITICAL 修復驗收 1（他人檔案不可被綁定）：
         * 檔案 A 的 uploader 是 userX，文章 B 的作者是 userY → bindToArticle(B, [A]) 不會綁定 A。
         */
        @Test
        @DisplayName("bindToArticle_whenFileOwnedByDifferentUser_doesNotBindAndLeavesArticleUuidUnchanged")
        void bindToArticle_whenFileOwnedByDifferentUser_doesNotBindAndLeavesArticleUuidUnchanged() {
            UUID articleB = UUID.randomUUID();
            UUID userX = UUID.randomUUID();
            UUID userY = UUID.randomUUID();
            UUID fileA = UUID.randomUUID();
            stubArticleAuthor(articleB, userY);
            when(fileMetadataRepository.findByArticleUuid(articleB)).thenReturn(List.of());
            stubFindAll(fileOf(fileA, userX, null));

            fileService.bindToArticle(articleB, List.of(fileA));

            verifyNoWrites();
        }

        /**
         * CRITICAL 修復驗收 2（不可竊取他人已發布文章的圖）：
         * 檔案 A 已綁定 userX 的文章 P，userY 的文章 Q 嘗試綁定 A → A 不被改綁。
         */
        @Test
        @DisplayName("bindToArticle_whenAttackerTriesToStealAnotherArticlesFile_fileStaysWithOriginalArticle")
        void bindToArticle_whenAttackerTriesToStealAnotherArticlesFile_fileStaysWithOriginalArticle() {
            UUID articleP = UUID.randomUUID();
            UUID articleQ = UUID.randomUUID();
            UUID userX = UUID.randomUUID();
            UUID userY = UUID.randomUUID();
            UUID fileA = UUID.randomUUID();
            stubArticleAuthor(articleQ, userY);
            when(fileMetadataRepository.findByArticleUuid(articleQ)).thenReturn(List.of());
            stubFindAll(fileOf(fileA, userX, articleP));

            fileService.bindToArticle(articleQ, List.of(fileA));

            verifyNoWrites();
        }

        /** 同一作者的檔案已綁定在另一篇文章：沿用既有語意，改綁到本文章 */
        @Test
        @DisplayName("bindToArticle_whenSameAuthorFileBoundToAnotherArticle_rebindsToThisArticle")
        void bindToArticle_whenSameAuthorFileBoundToAnotherArticle_rebindsToThisArticle() {
            UUID otherArticle = UUID.randomUUID();
            UUID thisArticle = UUID.randomUUID();
            UUID author = UUID.randomUUID();
            UUID file = UUID.randomUUID();
            stubArticleAuthor(thisArticle, author);
            when(fileMetadataRepository.findByArticleUuid(thisArticle)).thenReturn(List.of());
            stubFindAll(fileOf(file, author, otherArticle));

            fileService.bindToArticle(thisArticle, List.of(file));

            verifyBoundExactly(thisArticle, file);
        }

        /** fail-safe：文章不存在 → 不綁定任何檔案、不拋錯 */
        @Test
        @DisplayName("bindToArticle_whenArticleNotFound_bindsNothingAndDoesNotThrow")
        void bindToArticle_whenArticleNotFound_bindsNothingAndDoesNotThrow() {
            UUID articleUuid = UUID.randomUUID();
            UUID fileA = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());
            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of());
            stubFindAll(fileOf(fileA, UUID.randomUUID(), null));

            assertThatCode(() -> fileService.bindToArticle(articleUuid, List.of(fileA)))
                    .doesNotThrowAnyException();

            verifyNoWrites();
        }

        /** fail-safe：文章存在但 ArticleData.authorUuid 為 null（資料異常）→ 不綁定任何檔案、不拋錯 */
        @Test
        @DisplayName("bindToArticle_whenAuthorUuidNotResolvable_bindsNothingAndDoesNotThrow")
        void bindToArticle_whenAuthorUuidNotResolvable_bindsNothingAndDoesNotThrow() {
            UUID articleUuid = UUID.randomUUID();
            UUID fileA = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "DRAFT", null, null, null)));
            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of());
            stubFindAll(fileOf(fileA, UUID.randomUUID(), null));

            assertThatCode(() -> fileService.bindToArticle(articleUuid, List.of(fileA)))
                    .doesNotThrowAnyException();

            verifyNoWrites();
        }

        /**
         * fail-safe 的真正含義：解析不到文章作者時「什麼都不動」，包含既有綁定。
         *
         * <p>原實作先跑解綁迴圈、再解析作者並於 null 時 return，等於在 articleLookupFacade
         * 暫時查不到（文章剛被刪、或跨模組查詢瞬間失敗）時把該文章所有既有綁定清空且不會補回
         * ——已發布文章的圖片全部退回「未綁定 = 私有」，讀者端整篇破圖。這正是 bindToArticle 完整替換語意
         * javadoc 警告的「權限殘留反向問題」。</p>
         */
        @Test
        @DisplayName("bindToArticle_whenArticleNotFound_doesNotUnbindExistingFiles")
        void bindToArticle_whenArticleNotFound_doesNotUnbindExistingFiles() {
            UUID articleUuid = UUID.randomUUID();
            FileMetadata alreadyBound = fileOf(UUID.randomUUID(), UUID.randomUUID(), articleUuid);
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());
            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of(alreadyBound));

            assertThatCode(() -> fileService.bindToArticle(articleUuid, List.of(UUID.randomUUID())))
                    .as("解析不到文章作者時不可解除既有綁定，否則已發布文章的圖片會全部變回私有")
                    .doesNotThrowAnyException();

            verifyNoWrites();
        }

        /** 同上，但失敗點在 authorUuid（文章存在，但 ArticleData.authorUuid 為 null） */
        @Test
        @DisplayName("bindToArticle_whenAuthorUuidNotResolvable_doesNotUnbindExistingFiles")
        void bindToArticle_whenAuthorUuidNotResolvable_doesNotUnbindExistingFiles() {
            UUID articleUuid = UUID.randomUUID();
            FileMetadata alreadyBound = fileOf(UUID.randomUUID(), UUID.randomUUID(), articleUuid);
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "PUBLISHED", null, null, null)));
            when(fileMetadataRepository.findByArticleUuid(articleUuid)).thenReturn(List.of(alreadyBound));

            assertThatCode(() -> fileService.bindToArticle(articleUuid, List.of(UUID.randomUUID())))
                    .doesNotThrowAnyException();

            verifyNoWrites();
        }

        /**
         * 對照組：明確傳空清單仍必須解除全部綁定（「完整替換」語意的合法用法），
         * 此時不需要也不應該要求解析得到文章作者。確保上面的 fail-safe 修正
         * 不會把「清空綁定」這條正常路徑一併擋掉。
         */
        @Test
        @DisplayName("bindToArticle_withEmptyList_unbindsAllEvenWhenArticleNotFound")
        void bindToArticle_withEmptyList_unbindsAllEvenWhenArticleNotFound() {
            UUID articleUuid = UUID.randomUUID();
            UUID boundId = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());
            when(fileMetadataRepository.findByArticleUuid(articleUuid))
                    .thenReturn(List.of(fileOf(boundId, UUID.randomUUID(), articleUuid)));

            fileService.bindToArticle(articleUuid, List.of());

            verifyUnboundExactly(articleUuid, boundId);
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
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "DRAFT", null, uploaderId, null)));
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
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "PUBLISHED", null, actualAuthorUuid, null)));
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, uploaderId, "AUTHOR", articleUuid);

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getArticleUuid()).isNull();
        }

        /**
         * 安全 fail-safe：文章存在但 {@code ArticleData.authorUuid} 為 null（資料異常）→ 視為無法確認擁有權，
         * 不綁定（存成 null），不拋錯。若誤把「作者未知」當成通過，任何人都能把檔案綁到這篇文章。
         */
        @Test
        @DisplayName("uploadFile_withArticleWhoseAuthorUuidIsNull_savesWithNullArticleUuid")
        void uploadFile_withArticleWhoseAuthorUuidIsNull_savesWithNullArticleUuid() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            UUID articleUuid = UUID.randomUUID();
            UUID uploaderId = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "DRAFT", null, null, null)));
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, uploaderId, "AUTHOR", articleUuid);

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            assertThat(captor.getValue().getArticleUuid()).isNull();
        }

        /**
         * 安全 fail-safe：articleUuid 對應的文章不存在 → 視為無法確認擁有權，存成 null，不拋錯。
         */
        @Test
        @DisplayName("uploadFile_withNonExistentArticle_savesWithNullArticleUuid")
        void uploadFile_withNonExistentArticle_savesWithNullArticleUuid() throws Exception {
            byte[] jpegBytes = minimalJpegBytes();
            MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR", articleUuid);

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

    /** W5A：openContent —— 以 MinIO getObject 串流物件內容（取代 presigned URL 302） */
    @Nested
    @DisplayName("openContent 測試 (W5A)")
    class OpenContentTests {

        /** 以 getObject（bucket + storagePath）取得串流，並原樣回傳其內容，不整檔讀入記憶體 */
        @Test
        @DisplayName("openContent_whenObjectExists_returnsStreamFromGetObject")
        void openContent_whenObjectExists_returnsStreamFromGetObject() throws Exception {
            FileMetadata metadata = new FileMetadata();
            metadata.setId(UUID.randomUUID());
            metadata.setStoragePath("articles/2024/01/01/test.jpg");
            byte[] bytes = {1, 2, 3, 4};
            when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(
                    new GetObjectResponse(Headers.of(), "test-bucket", "", "articles/2024/01/01/test.jpg",
                            new ByteArrayInputStream(bytes)));

            try (InputStream in = fileService.openContent(metadata)) {
                assertThat(in.readAllBytes()).isEqualTo(bytes);
            }

            ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
            verify(minioClient).getObject(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
            assertThat(captor.getValue().object()).isEqualTo("articles/2024/01/01/test.jpg");
        }

        /** 物件儲存失敗 → STORAGE_ERROR（SystemException），不洩漏底層例外細節 */
        @Test
        @DisplayName("openContent_whenStorageFails_throwsSystemException")
        void openContent_whenStorageFails_throwsSystemException() throws Exception {
            FileMetadata metadata = new FileMetadata();
            metadata.setStoragePath("articles/x.jpg");
            when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(new IOException("boom"));

            assertThatThrownBy(() -> fileService.openContent(metadata))
                    .isInstanceOf(SystemException.class);
        }
    }

    /** W5A：isPubliclyReadable —— 授權矩陣中與請求者身分無關的部分，決定可否被共用快取 */
    @Nested
    @DisplayName("isPubliclyReadable 測試 (W5A)")
    class IsPubliclyReadableTests {

        private FileMetadata metadata(UsageType usageType, UUID articleUuid) {
            FileMetadata m = new FileMetadata();
            m.setId(UUID.randomUUID());
            m.setUsageType(usageType);
            m.setArticleUuid(articleUuid);
            m.setUploaderId(UUID.randomUUID());
            return m;
        }

        @Test
        @DisplayName("isPubliclyReadable_avatar_true")
        void isPubliclyReadable_avatar_true() {
            assertThat(fileService.isPubliclyReadable(metadata(UsageType.AVATAR, null))).isTrue();
        }

        @Test
        @DisplayName("isPubliclyReadable_boundToPublishedArticle_true")
        void isPubliclyReadable_boundToPublishedArticle_true() {
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "PUBLISHED", null, UUID.randomUUID(), null)));

            assertThat(fileService.isPubliclyReadable(metadata(UsageType.ARTICLE_CONTENT, articleUuid))).isTrue();
        }

        @Test
        @DisplayName("isPubliclyReadable_boundToDraftArticle_false")
        void isPubliclyReadable_boundToDraftArticle_false() {
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(articleUuid, "DRAFT", null, UUID.randomUUID(), null)));

            assertThat(fileService.isPubliclyReadable(metadata(UsageType.ARTICLE_CONTENT, articleUuid))).isFalse();
        }

        @Test
        @DisplayName("isPubliclyReadable_unbound_false")
        void isPubliclyReadable_unbound_false() {
            assertThat(fileService.isPubliclyReadable(metadata(UsageType.ARTICLE_CONTENT, null))).isFalse();
        }

        @Test
        @DisplayName("isPubliclyReadable_boundArticleMissing_false")
        void isPubliclyReadable_boundArticleMissing_false() {
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());

            assertThat(fileService.isPubliclyReadable(metadata(UsageType.ARTICLE_CONTENT, articleUuid))).isFalse();
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

    /** FILE-03：storage key 的副檔名只來自偵測出的 MIME 白名單，完全忽略使用者檔名 */
    @Nested
    @DisplayName("uploadFile 副檔名白名單（FILE-03）")
    class UploadFileExtensionWhitelistTests {

        private byte[] pngBytes() {
            return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};
        }

        @ParameterizedTest
        @ValueSource(strings = {"x.php", "../../a.png", "a.png%00.exe", "noext", "a.PNG.jsp", "evil.png/../../etc"})
        @DisplayName("uploadFile_withHostileFilenameButRealPng_storageKeyEndsWithPngAndHasNoDotDot")
        void uploadFile_withHostileFilenameButRealPng_storageKeyEndsWithPngAndHasNoDotDot(String filename) {
            MockMultipartFile file = new MockMultipartFile("file", filename, "image/png", pngBytes());
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "AUTHOR");

            ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
            verify(fileMetadataRepository).save(captor.capture());
            String key = captor.getValue().getStoragePath();
            assertThat(key).endsWith(".png").doesNotContain("..").doesNotContain("%").doesNotContain("php");
            assertThat(key).matches("articles/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.png");
        }
    }

    /** RACE-12：配額 check-then-act —— 交易內加鎖後重新加總，超額則 rollback 並於交易外刪除剛上傳的物件 */
    @Nested
    @DisplayName("uploadFile 配額並發保護（RACE-12）")
    class UploadFileQuotaRaceTests {

        @Test
        @DisplayName("uploadFile_whenRecheckInsideLockExceedsQuota_deletesUploadedObjectAndDoesNotSave")
        void uploadFile_whenRecheckInsideLockExceedsQuota_deletesUploadedObjectAndDoesNotSave() throws Exception {
            MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", minimalJpegBytes());
            UUID uploaderId = UUID.randomUUID();
            /* 第一次（不加鎖的快速預檢）看起來還有空間；加鎖後重新加總發現已被並發上傳占滿 */
            when(fileMetadataRepository.sumSizeByUploaderId(uploaderId)).thenReturn(0L, 10L * 1024 * 1024);

            assertThatThrownBy(() -> fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, uploaderId, "USER"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(FileErrorCode.QUOTA_EXCEEDED.getMessage());

            ArgumentCaptor<PutObjectArgs> put = ArgumentCaptor.forClass(PutObjectArgs.class);
            ArgumentCaptor<RemoveObjectArgs> remove = ArgumentCaptor.forClass(RemoveObjectArgs.class);
            verify(minioClient).putObject(put.capture());
            verify(minioClient).removeObject(remove.capture());
            assertThat(remove.getValue().object()).isEqualTo(put.getValue().object());
            verify(fileMetadataRepository, never()).save(any());
            verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
        }

        @Test
        @DisplayName("uploadFile_acquiresUploaderLockBeforeRecheckAndAfterMinioUpload")
        void uploadFile_acquiresUploaderLockBeforeRecheckAndAfterMinioUpload() throws Exception {
            MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", minimalJpegBytes());
            UUID uploaderId = UUID.randomUUID();
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(0L);
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, uploaderId, "USER");

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(minioClient, fileMetadataRepository);
            order.verify(minioClient).putObject(any(PutObjectArgs.class));
            order.verify(fileMetadataRepository).acquireQuotaLock(
                    FileMetadataRepository.QUOTA_LOCK_KEY_PREFIX + uploaderId);
            order.verify(fileMetadataRepository).sumSizeByUploaderId(uploaderId);
            order.verify(fileMetadataRepository).save(any(FileMetadata.class));
        }

        @Test
        @DisplayName("uploadFile_withAdminRole_doesNotTakeQuotaLock")
        void uploadFile_withAdminRole_doesNotTakeQuotaLock() throws Exception {
            MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", minimalJpegBytes());
            when(fileMetadataRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "ADMIN");

            verify(fileMetadataRepository, never()).acquireQuotaLock(any());
        }

        @Test
        @DisplayName("uploadFile_quotaPreCheckRejectsObviousOverage_beforeAnyMinioUpload")
        void uploadFile_quotaPreCheckRejectsObviousOverage_beforeAnyMinioUpload() throws Exception {
            MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", minimalJpegBytes());
            when(fileMetadataRepository.sumSizeByUploaderId(any())).thenReturn(10L * 1024 * 1024);

            assertThatThrownBy(() -> fileService.uploadFile(file, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), "USER"))
                    .isInstanceOf(BusinessException.class);

            verify(minioClient, never()).putObject(any(PutObjectArgs.class));
            verify(fileMetadataRepository, never()).acquireQuotaLock(any());
        }
    }

    /** AUTH-11（b）：getUserFiles 真正套用分頁 */
    @Nested
    @DisplayName("getUserFiles 分頁 (AUTH-11 b)")
    class GetUserFilesPagingTests {

        @Test
        @DisplayName("getUserFiles_withPageable_queriesOnlyThatPage")
        void getUserFiles_withPageable_queriesOnlyThatPage() {
            UUID uploader = UUID.randomUUID();
            FileMetadata a = new FileMetadata();
            FileMetadata b = new FileMetadata();
            when(fileMetadataRepository.findPageByUploaderId(uploader, 2, 4L)).thenReturn(List.of(a, b));

            List<FileMetadata> result = fileService.getUserFiles(uploader, org.springframework.data.domain.PageRequest.of(2, 2));

            assertThat(result).containsExactly(a, b);
            verify(fileMetadataRepository, never()).findByUploaderIdOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("getUserFiles_withSizeAboveMax_throwsParamValidError")
        void getUserFiles_withSizeAboveMax_throwsParamValidError() {
            UUID uploader = UUID.randomUUID();

            assertThatThrownBy(() -> fileService.getUserFiles(uploader,
                    org.springframework.data.domain.PageRequest.of(0, 101)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("100");
            verify(fileMetadataRepository, never()).findPageByUploaderId(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("getUserFiles_withUnpaged_usesDefaultFirstPage")
        void getUserFiles_withUnpaged_usesDefaultFirstPage() {
            UUID uploader = UUID.randomUUID();
            when(fileMetadataRepository.findPageByUploaderId(uploader, 20, 0L)).thenReturn(List.of());

            assertThat(fileService.getUserFiles(uploader, org.springframework.data.domain.Pageable.unpaged())).isEmpty();
        }
    }
}
