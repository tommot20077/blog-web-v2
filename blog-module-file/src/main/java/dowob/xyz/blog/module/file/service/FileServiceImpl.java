package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.SystemException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.file.config.FileProperties;
import dowob.xyz.blog.module.file.config.FileRabbitMqConfig;
import dowob.xyz.blog.module.file.event.ImageUploadedEvent;
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

import java.io.ByteArrayInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 檔案服務實作
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    /** 單檔最大大小：5MB */
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    /** 檔案模組設定（配額、允許的 MIME 類型） */
    private final FileProperties fileProperties;

    /** 檔案元資料 Repository */
    private final FileMetadataRepository fileMetadataRepository;

    /** MinIO 客戶端 */
    private final MinioClient minioClient;

    /** RabbitMQ 訊息發送模板 */
    private final RabbitTemplate rabbitTemplate;

    /** Spring 宣告式事務模板（用於縮小 uploadFile 的事務範圍） */
    private final TransactionTemplate transactionTemplate;

    /** 文章模組跨模組 Facade（canRead 判斷「已綁定文章是否已發布」用；不得直接查 article 表） */
    private final ArticleFacade articleFacade;

    /** Apache Tika MIME 類型偵測器（執行緒安全，可重用單一實例） */
    private final Tika tika = new Tika();

    /** MinIO 儲存桶名稱 */
    @Value("${minio.bucket-name}")
    private String bucketName;

    /**
     * 上傳檔案至 MinIO 並儲存元資料，可選擇性一併綁定至文章
     *
     * <p>
     * {@code articleUuid} 有值時直接設定於新建立的 {@link FileMetadata}（非透過 {@link #bindToArticle}，
     * 見 {@link FileService#uploadFile(MultipartFile, UsageType, UUID, String, UUID)} javadoc 說明原因）。
     * </p>
     *
     * @param file         上傳的 MultipartFile
     * @param usageType    檔案用途類型
     * @param uploaderId   上傳者 UUID
     * @param uploaderRole 上傳者角色字串
     * @param articleUuid  要一併綁定的文章 UUID；可為 null
     * @return 上傳成功的檔案回應資訊
     */
    @Override
    public FileUploadResponse uploadFile(MultipartFile file, UsageType usageType, UUID uploaderId, String uploaderRole, UUID articleUuid) {
        /** F-2: 一次性讀取 bytes，避免多次消耗 InputStream */
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            log.error("讀取上傳檔案內容失敗", e);
            throw new SystemException(CommonErrorCode.FILE_IO_ERROR);
        }

        String detectedMimeType = detectMimeType(fileBytes, file.getOriginalFilename());
        if (!fileProperties.allowedMimeTypes().contains(detectedMimeType)) {
            throw new BusinessException(FileErrorCode.INVALID_FILE_TYPE);
        }
        if (fileBytes.length > MAX_FILE_SIZE) {
            throw new BusinessException(FileErrorCode.FILE_TOO_LARGE);
        }
        long quota = resolveQuota(uploaderRole);
        long used = fileMetadataRepository.sumSizeByUploaderId(uploaderId);
        if (quota != Long.MAX_VALUE && (used + fileBytes.length) > quota) {
            throw new BusinessException(FileErrorCode.QUOTA_EXCEEDED);
        }
        String ext = extractExtension(file.getOriginalFilename());
        UUID fileId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        String prefix = (usageType == UsageType.AVATAR) ? "avatars" : "articles";
        String storagePath = String.format("%s/%d/%02d/%02d/%s.%s",
                prefix, now.getYear(), now.getMonthValue(), now.getDayOfMonth(), fileId, ext);
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(storagePath)
                            .stream(new ByteArrayInputStream(fileBytes), fileBytes.length, -1)
                            .contentType(detectedMimeType)
                            .build());
        } catch (Exception e) {
            log.error("MinIO 上傳失敗: {}", storagePath, e);
            throw new SystemException(CommonErrorCode.STORAGE_ERROR);
        }
        Integer width = null;
        Integer height = null;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(fileBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException e) {
            log.warn("無法讀取圖片尺寸: {}", e.getMessage());
        }
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        /** F-4: 正規化 originalName */
        metadata.setOriginalName(sanitizeOriginalName(file.getOriginalFilename()));
        metadata.setStoragePath(storagePath);
        metadata.setContentType(detectedMimeType);
        metadata.setSize((long) fileBytes.length);
        metadata.setWidth(width);
        metadata.setHeight(height);
        metadata.setUsageType(usageType);
        metadata.setHasThumbnail(false);
        metadata.setUploaderId(uploaderId);
        metadata.setCreatedAt(now);
        metadata.setNewEntity(true);
        /**
         * B4：可選 articleUuid 直接綁定。刻意不呼叫 bindToArticle——
         * 該方法是「完整替換」語意，對單一新檔案呼叫會誤解除同文章其他既有綁定檔案。
         * 這裡是新建立的 metadata（尚未存在於 DB），直接 set 不影響任何其他列。
         */
        metadata.setArticleUuid(articleUuid);
        /** F-3: 僅 DB 操作在事務內；DB 失敗時補償刪除 MinIO 檔案 */
        try {
            transactionTemplate.executeWithoutResult(status -> fileMetadataRepository.save(metadata));
        } catch (Exception e) {
            compensateMinioDelete(storagePath);
            throw e;
        }
        /** DB 已 commit，best-effort 發 MQ（失敗不影響上傳結果） */
        try {
            rabbitTemplate.convertAndSend(
                    FileRabbitMqConfig.EXCHANGE,
                    FileRabbitMqConfig.IMAGE_UPLOADED_KEY,
                    new ImageUploadedEvent(fileId, storagePath, detectedMimeType));
        } catch (Exception e) {
            log.warn("MQ 發送失敗（best-effort），不影響上傳結果: {}", storagePath, e);
        }
        /**
         * B4 / spec §3.1：回傳相對路徑而非 MinIO 直連網址，避免絕對網址把域名焊進 markdown 內文
         * （換域名時內文全破）。瀏覽器對 {@code /api/...} 以當前 origin 解析是 HTML 內建行為，
         * 不需要 render-time 改寫。實際內容由 {@code GET /api/v1/files/{id}/content} 端點
         * 判斷授權後 302 導向 MinIO presigned URL 取得。
         */
        String url = "/api/v1/files/" + fileId + "/content";
        return new FileUploadResponse(fileId, url, width, height, (long) fileBytes.length, usageType);
    }

    /**
     * 刪除指定檔案
     *
     * @param fileId      要刪除的檔案 UUID
     * @param requesterId 請求刪除的使用者 UUID
     * @param isAdmin     是否為管理員
     */
    @Transactional
    @Override
    public void deleteFile(UUID fileId, UUID requesterId, boolean isAdmin) {
        FileMetadata metadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(FileErrorCode.FILE_NOT_FOUND));
        if (!isAdmin && !metadata.belongsTo(requesterId)) {
            throw new BusinessException(FileErrorCode.FILE_ACCESS_DENIED);
        }
        fileMetadataRepository.deleteById(fileId);
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(metadata.getStoragePath())
                            .build());
            if (Boolean.TRUE.equals(metadata.getHasThumbnail())) {
                String thumbPath = buildThumbPath(metadata.getStoragePath());
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(bucketName)
                                .object(thumbPath)
                                .build());
            }
        } catch (Exception e) {
            log.error("MinIO 刪除失敗: {}", metadata.getStoragePath(), e);
            throw new SystemException(CommonErrorCode.STORAGE_ERROR);
        }
    }

    /**
     * 查詢使用者的儲存配額資訊
     *
     * @param uploaderId   使用者 UUID
     * @param uploaderRole 使用者角色字串
     * @return 配額回應資訊
     */
    @Override
    public QuotaResponse getQuota(UUID uploaderId, String uploaderRole) {
        long used = fileMetadataRepository.sumSizeByUploaderId(uploaderId);
        long limit = resolveQuota(uploaderRole);
        long remaining = (limit == Long.MAX_VALUE) ? Long.MAX_VALUE : Math.max(0, limit - used);
        return new QuotaResponse(used, limit, remaining);
    }

    /**
     * 依 ID 取得單一檔案元資料
     *
     * @param fileId 檔案 UUID
     * @return 檔案元資料
     */
    @Override
    public FileMetadata getFileMetadata(UUID fileId) {
        return fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(FileErrorCode.FILE_NOT_FOUND));
    }

    /**
     * 取得使用者上傳的所有檔案
     *
     * @param uploaderId 使用者 UUID
     * @param pageable   分頁參數
     * @return 檔案元資料列表
     */
    @Override
    public List<FileMetadata> getUserFiles(UUID uploaderId, Pageable pageable) {
        return fileMetadataRepository.findByUploaderIdOrderByCreatedAtDesc(uploaderId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * 完整替換語意：先解除「目前已綁定此文章、但不在本次清單內」的舊檔案（設回 null），
     * 再將清單內的檔案綁定至此文章。清單內若含不存在的 fileUuid，安靜略過
     * （呼叫端如 blog-module-article 掃描 content 取得的 UUID 可能是使用者輸入的壞連結，
     * 不應讓文章儲存因此失敗）。
     * </p>
     */
    @Override
    public void bindToArticle(UUID articleUuid, List<UUID> fileUuids) {
        List<UUID> targetIds = (fileUuids != null) ? fileUuids : List.of();

        List<FileMetadata> currentlyBound = fileMetadataRepository.findByArticleUuid(articleUuid);
        for (FileMetadata bound : currentlyBound) {
            if (!targetIds.contains(bound.getId())) {
                bound.setArticleUuid(null);
                fileMetadataRepository.save(bound);
            }
        }

        for (UUID fileUuid : targetIds) {
            fileMetadataRepository.findById(fileUuid).ifPresent(metadata -> {
                if (!articleUuid.equals(metadata.getArticleUuid())) {
                    metadata.setArticleUuid(articleUuid);
                    fileMetadataRepository.save(metadata);
                }
            });
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * 依 spec §4 授權矩陣依序判斷；「已綁定文章是否已發布」透過 {@link ArticleFacade#findByUuid}
     * 取得（不得直接查 article 表或注入 article 模組 repository，見 architecture.md）。
     * 未綁定（{@code articleUuid == null}）或綁定的文章查無資料，一律視為不公開（fail-safe）。
     * </p>
     */
    @Override
    public boolean canRead(UUID fileId, UUID requesterId, boolean isAdmin) {
        FileMetadata metadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(FileErrorCode.FILE_NOT_FOUND));

        if (metadata.getUsageType() == UsageType.AVATAR) {
            return true;
        }
        if (metadata.getArticleUuid() != null) {
            Optional<ArticleData> boundArticle = articleFacade.findByUuid(metadata.getArticleUuid());
            if (boundArticle.isPresent() && ArticleStatus.isPubliclyVisible(boundArticle.get().status())) {
                return true;
            }
        }
        if (metadata.belongsTo(requesterId)) {
            return true;
        }
        return isAdmin;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * 使用 MinIO SDK {@code getPresignedObjectUrl}，方法固定為 GET，效期固定 5 分鐘（300 秒）。
     * 本方法不做任何權限判斷，呼叫端（{@code FileController}）須先呼叫 {@link #canRead} 通過後才可呼叫。
     * </p>
     */
    @Override
    public String generatePresignedUrl(UUID fileId) {
        FileMetadata metadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(FileErrorCode.FILE_NOT_FOUND));
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(metadata.getStoragePath())
                            .expiry(5, TimeUnit.MINUTES)
                            .build());
        } catch (Exception e) {
            log.error("產生 MinIO 簽名網址失敗: {}", metadata.getStoragePath(), e);
            throw new SystemException(CommonErrorCode.STORAGE_ERROR);
        }
    }

    /**
     * 使用 Apache Tika 偵測 MIME 類型（F-2：改用 byte[] 避免重複消耗 InputStream）
     *
     * @param fileBytes 檔案位元組陣列
     * @param filename  原始檔案名稱（供 Tika 輔助判斷）
     * @return 偵測到的 MIME 類型字串
     */
    private String detectMimeType(byte[] fileBytes, String filename) {
        try {
            return tika.detect(new ByteArrayInputStream(fileBytes), filename);
        } catch (IOException e) {
            log.error("MIME 類型偵測失敗: {}", filename, e);
            throw new SystemException(CommonErrorCode.FILE_IO_ERROR);
        }
    }

    /**
     * F-3: MinIO 上傳成功但 DB 儲存失敗時的補償刪除
     *
     * @param storagePath MinIO 物件路徑
     */
    private void compensateMinioDelete(String storagePath) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName).object(storagePath).build());
            log.info("補償刪除 MinIO 檔案成功: {}", storagePath);
        } catch (Exception ex) {
            log.error("補償刪除 MinIO 檔案失敗，產生孤兒: {}", storagePath, ex);
        }
    }

    /**
     * F-4: 正規化 originalName：null/blank → "unnamed"；超過 255 字元 → 保留副檔名截斷
     *
     * @param originalFilename 原始檔案名稱（可能為 null）
     * @return 安全的檔案名稱（不超過 255 字元）
     */
    private String sanitizeOriginalName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unnamed";
        }
        if (originalFilename.length() <= 255) {
            return originalFilename;
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0) {
            return originalFilename.substring(0, 255);
        }
        String ext = originalFilename.substring(dotIndex);
        String base = originalFilename.substring(0, dotIndex);
        int allowedBase = 255 - ext.length();
        if (allowedBase <= 0) {
            return originalFilename.substring(0, 255);
        }
        return base.substring(0, Math.min(base.length(), allowedBase)) + ext;
    }

    /**
     * 依角色解析配額上限
     *
     * @param role 角色字串
     * @return 配額位元組數
     */
    private long resolveQuota(String role) {
        if ("ADMIN".equalsIgnoreCase(role)) {
            return Long.MAX_VALUE;
        }
        String key = role.toUpperCase();
        DataSize dataSize = fileProperties.quotas().getOrDefault(key,
                fileProperties.quotas().getOrDefault("USER", DataSize.ofMegabytes(10)));
        return dataSize.toBytes();
    }

    /**
     * 從檔案名稱提取副檔名
     *
     * @param filename 原始檔案名稱
     * @return 副檔名（不含點）
     */
    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
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
