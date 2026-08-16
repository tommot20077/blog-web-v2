package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.SystemException;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /**
     * 文章模組跨模組「最小依賴」查詢 Facade（canRead 判斷「已綁定文章是否已發布」、
     * bindToArticle / uploadFile 判斷文章作者用；不得直接查 article 表）。
     *
     * <p>
     * <b>刻意不注入 {@code ArticleFacade}</b>：那顆胖 Bean 的依賴閉包含
     * {@code ArticleService} 與 {@code ArticleFileBinder}，而 {@code ArticleFileBinder}
     * 反過來依賴 {@code FileFacade → FileServiceImpl}，會形成 Spring 建構子循環依賴
     * 導致應用程式完全無法啟動（成因與修法見 {@link ArticleLookupFacade} javadoc）。
     * 這裡改注入依賴閉包只有 {@code ArticleRepository} 的 {@link ArticleLookupFacade}，
     * 環不成立，授權判斷仍完整保留在本 service 層。
     * </p>
     */
    private final ArticleLookupFacade articleLookupFacade;

    /**
     * 使用者模組跨模組 Facade（將文章作者的 authorId（Long）轉換為 UUID，供 {@link #bindToArticle}
     * 與 {@link #uploadFile} 的擁有權比對用；見安全複審 CRITICAL / MEDIUM 1 修復）。
     */
    private final UserFacade userFacade;

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
     * <p>
     * <b>MEDIUM 1 修復</b>：{@code articleUuid} 僅在「呼叫者本人（{@code uploaderId}）即為該文章作者」時才會
     * 被接受並寫入；否則視為未綁定（存 null），並記錄可疑嘗試。避免使用者上傳檔案時宣稱其屬於他人的
     * （尤其是已發布）文章，使該檔案立即被 {@link #canRead} 判定為公開。見 {@link #resolveOwnedArticleUuid}。
     * </p>
     *
     * @param file         上傳的 MultipartFile
     * @param usageType    檔案用途類型
     * @param uploaderId   上傳者 UUID
     * @param uploaderRole 上傳者角色字串
     * @param articleUuid  要一併綁定的文章 UUID；可為 null；非本人文章時會被忽略（存 null）
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
         * MEDIUM 1：先驗證擁有權，非本人文章一律存 null（fail-safe）。
         */
        metadata.setArticleUuid(resolveOwnedArticleUuid(articleUuid, uploaderId));
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
     *
     * <p>
     * <b>CRITICAL 修復（安全複審）：擁有權不變量</b>——一個檔案只能被綁定到「該檔案的上傳者
     * == 該文章的作者」的文章上。否則安靜略過並記錄可疑嘗試（{@code log.warn}），不拋錯，
     * 因為清單內容常源自使用者輸入的內文掃描，不應讓文章儲存因此失敗。
     * </p>
     *
     * <p>
     * <b>fail-safe 的順序很重要</b>：解析不到文章作者時必須<b>在解綁之前</b>就整個放棄，
     * 什麼都不動。若先跑解綁迴圈才發現作者解析失敗（文章剛被刪、或跨模組查詢瞬間失敗）並
     * return，該文章的既有綁定已被清空且不會補回——已發布文章的圖片會全部退回
     * 「未綁定 = 私有」，讀者端整篇破圖，正是 {@code FileFacade} javadoc 警告的
     * 「權限殘留反向問題」。唯一例外是呼叫端<b>明確傳入空清單</b>：那是「清除本文章所有綁定」
     * 的合法用法，語意上不需要作者資訊，因此不受此前置檢查限制。
     * </p>
     *
     * <p>
     * 全程標註 {@link Transactional}：解綁與綁定是同一次「完整替換」的兩半，中途拋例外
     * 若各自獨立提交會留下半套綁定狀態（呼叫端 {@code ArticleFileBinder} 以 best-effort
     * 吞掉例外，不會有人重試）。
     * </p>
     */
    @Override
    @Transactional
    public void bindToArticle(UUID articleUuid, List<UUID> fileUuids) {
        List<UUID> targetIds = (fileUuids != null) ? fileUuids : List.of();

        /*
         * 先解析作者再動任何資料：解析不到就整個放棄（含解綁），避免把既有綁定清空後才失敗。
         * 空清單是「清除全部綁定」的明確指令，不需要作者資訊，故跳過此前置檢查。
         */
        UUID authorUuid = null;
        if (!targetIds.isEmpty()) {
            authorUuid = resolveArticleAuthorUuid(articleUuid);
            if (authorUuid == null) {
                log.warn("bindToArticle：無法解析文章作者 UUID，fail-safe 完全不動綁定"
                        + "（含既有綁定，避免已發布文章圖片被誤設為私有）。articleUuid={}", articleUuid);
                return;
            }
        }

        Set<UUID> targetIdSet = new HashSet<>(targetIds);
        List<FileMetadata> currentlyBound = fileMetadataRepository.findByArticleUuid(articleUuid);
        for (FileMetadata bound : currentlyBound) {
            if (!targetIdSet.contains(bound.getId())) {
                bound.setArticleUuid(null);
                fileMetadataRepository.save(bound);
            }
        }

        if (targetIds.isEmpty()) {
            return;
        }

        /* lambda 需要 effectively final 的擷取變數 */
        final UUID articleAuthorUuid = authorUuid;
        for (UUID fileUuid : targetIds) {
            fileMetadataRepository.findById(fileUuid).ifPresent(metadata -> {
                if (articleUuid.equals(metadata.getArticleUuid())) {
                    return;
                }
                if (!articleAuthorUuid.equals(metadata.getUploaderId())) {
                    log.warn("bindToArticle：拒絕綁定非本文章作者上傳的檔案（可疑嘗試）。"
                                    + "fileUuid={}, articleUuid={}, uploaderId={}, articleAuthorUuid={}",
                            fileUuid, articleUuid, metadata.getUploaderId(), articleAuthorUuid);
                    return;
                }
                metadata.setArticleUuid(articleUuid);
                fileMetadataRepository.save(metadata);
            });
        }
    }

    /**
     * 解析文章作者的 UUID（跨模組：article authorId（Long）→ user UUID）。
     *
     * <p>用於 {@link #bindToArticle} 與 {@link #resolveOwnedArticleUuid} 的擁有權比對。
     * 文章不存在、或其作者 UUID 無法解析時，皆回傳 null（fail-safe，由呼叫端決定如何處理）。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 作者 UUID；無法解析時為 null
     */
    private UUID resolveArticleAuthorUuid(UUID articleUuid) {
        return articleLookupFacade.findByUuid(articleUuid)
                .map(ArticleData::authorId)
                .flatMap(userFacade::getUserUuidById)
                .orElse(null);
    }

    /**
     * 驗證 {@link #uploadFile} 可選的 {@code articleUuid} 參數：僅當呼叫者本人即為該文章作者時
     * 才接受綁定，否則視為未綁定（fail-safe），並記錄可疑嘗試（MEDIUM 1 修復）。
     *
     * @param articleUuid 呼叫端宣稱要綁定的文章 UUID；可為 null
     * @param uploaderId  本次上傳者 UUID
     * @return 驗證通過的 articleUuid；未通過驗證或 articleUuid 為 null 時回傳 null
     */
    private UUID resolveOwnedArticleUuid(UUID articleUuid, UUID uploaderId) {
        if (articleUuid == null) {
            return null;
        }
        UUID authorUuid = resolveArticleAuthorUuid(articleUuid);
        if (authorUuid != null && authorUuid.equals(uploaderId)) {
            return articleUuid;
        }
        log.warn("uploadFile：忽略非本人文章的 articleUuid 綁定嘗試。articleUuid={}, uploaderId={}, resolvedAuthorUuid={}",
                articleUuid, uploaderId, authorUuid);
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * 依 spec §4 授權矩陣依序判斷；「已綁定文章是否已發布」透過 {@link ArticleLookupFacade#findByUuid}
     * 取得（不得直接查 article 表或注入 article 模組 repository，見 architecture.md）。
     * 未綁定（{@code articleUuid == null}）或綁定的文章查無資料，一律視為不公開（fail-safe）。
     * </p>
     */
    @Override
    public boolean canRead(UUID fileId, UUID requesterId, boolean isAdmin) {
        FileMetadata metadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(FileErrorCode.FILE_NOT_FOUND));
        return canRead(metadata, requesterId, isAdmin);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * 授權矩陣本體，接受已取得的 {@link FileMetadata}，<b>不再查一次 DB</b>。
     * 內文圖片是熱路徑（一頁十張圖就是十個 {@code /content} 請求），呼叫端若已經
     * 為了別的用途取過 metadata，就該重用它而不是讓授權判斷再查一遍。
     * </p>
     */
    @Override
    public boolean canRead(FileMetadata metadata, UUID requesterId, boolean isAdmin) {
        if (metadata.getUsageType() == UsageType.AVATAR) {
            return true;
        }
        if (metadata.getArticleUuid() != null) {
            Optional<ArticleData> boundArticle = articleLookupFacade.findByUuid(metadata.getArticleUuid());
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
        return generatePresignedUrl(metadata);
    }

    /**
     * {@inheritDoc}
     *
     * <p>接受已取得的 {@link FileMetadata}，不再查一次 DB（理由同 {@link #canRead(FileMetadata, UUID, boolean)}）。</p>
     */
    @Override
    public String generatePresignedUrl(FileMetadata metadata) {
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
