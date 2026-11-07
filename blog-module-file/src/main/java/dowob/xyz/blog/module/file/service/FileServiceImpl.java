package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.SystemException;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.file.config.FileProperties;
import dowob.xyz.blog.module.file.config.FileRabbitMqConfig;
import dowob.xyz.blog.module.file.event.ImageUploadedEvent;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.request.PageQuery;
import dowob.xyz.blog.common.api.errorcode.FileErrorCode;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.model.dto.FileUploadResponse;
import dowob.xyz.blog.module.file.model.dto.QuotaResponse;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

    /** 單檔最大大小：5MB（與 application.yaml 的 spring.servlet.multipart.max-file-size 對齊，由 MultipartYamlBindingTest 守住） */
    public static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

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
     * <b>刻意不注入 {@code ArticleFacade}</b>：那顆胖 Bean 的依賴閉包含 {@code ArticleService} 等整個
     * article 寫入路徑，file 只需要「作者是誰／目前狀態／內文」三項唯讀資料，改注入依賴閉包只有
     * {@code ArticleRepository} 的 {@link ArticleLookupFacade}（成因見其 javadoc），
     * 授權判斷仍完整保留在本 service 層。
     * </p>
     */
    private final ArticleLookupFacade articleLookupFacade;

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

        String detectedMimeType = detectMimeType(fileBytes);
        /* FILE-03：副檔名只來自白名單（由偵測出的 MIME 對應），不在白名單的 MIME 一律視為不支援 */
        String ext = AllowedImageTypes.extensionFor(detectedMimeType).orElse(null);
        if (ext == null || !fileProperties.allowedMimeTypes().contains(detectedMimeType)) {
            throw new BusinessException(FileErrorCode.INVALID_FILE_TYPE);
        }
        if (fileBytes.length > MAX_FILE_SIZE) {
            throw new BusinessException(FileErrorCode.FILE_TOO_LARGE);
        }
        /*
         * FILE-04（A6）：總像素上限（寬×高，不限單邊）。在寫入 MinIO 之前只讀 header 檢查，
         * 超限者不落地；讀不到寬高的檔案（ImageIO 無法解析）縮圖端也解不開，放行但不記錄寬高。
         */
        ImageDimensionReader.Dimensions dimensions = ImageDimensionReader.read(fileBytes).orElse(null);
        if (dimensions != null && dimensions.pixels() > fileProperties.maxImagePixels()) {
            log.warn("拒絕上傳：圖片總像素超過上限 {}x{}={} > {}, uploaderId={}",
                    dimensions.width(), dimensions.height(), dimensions.pixels(), fileProperties.maxImagePixels(), uploaderId);
            throw new BusinessException(FileErrorCode.INVALID_IMAGE_DIMENSIONS);
        }
        Integer width = dimensions == null ? null : (int) dimensions.width();
        Integer height = dimensions == null ? null : (int) dimensions.height();
        long quota = resolveQuota(uploaderRole);
        boolean quotaLimited = quota != Long.MAX_VALUE;
        /* RACE-12：不加鎖的快速預檢，明顯超額就直接拒絕（省一次 MinIO 上傳）；權威檢查在下方交易內加鎖後重做 */
        if (quotaLimited && (fileMetadataRepository.sumSizeByUploaderId(uploaderId) + fileBytes.length) > quota) {
            throw new BusinessException(FileErrorCode.QUOTA_EXCEEDED);
        }
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
        /**
         * F-3: 僅 DB 操作在事務內；DB 失敗（或配額重檢超額）時補償刪除 MinIO 檔案。
         *
         * <p>RACE-12：MinIO 上傳已在交易外完成（上傳期間不持有 DB 連線或鎖）。交易內先取得以上傳者為鍵的
         * advisory lock，再重新加總並比較——同一使用者的並發上傳在此序列化，後者會看到前者已 commit 的列，
         * 超額就丟 QUOTA_EXCEEDED、rollback，並由下方 catch 於交易外刪除剛上傳的物件。不同使用者鎖鍵不同，互不阻塞。
         * 無上限（ADMIN）者不需要配額檢查，也就不取鎖。</p>
         */
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (quotaLimited) {
                    fileMetadataRepository.acquireQuotaLock(FileMetadataRepository.QUOTA_LOCK_KEY_PREFIX + uploaderId);
                    long usedNow = fileMetadataRepository.sumSizeByUploaderId(uploaderId);
                    if ((usedNow + fileBytes.length) > quota) {
                        throw new BusinessException(FileErrorCode.QUOTA_EXCEEDED);
                    }
                }
                fileMetadataRepository.save(metadata);
            });
        } catch (Exception e) {
            compensateMinioDelete(storagePath);
            throw e;
        }
        /** DB 已 commit，best-effort 發 MQ（失敗不影響上傳結果） */
        try {
            rabbitTemplate.convertAndSend(
                    FileRabbitMqConfig.EXCHANGE,
                    FileRabbitMqConfig.IMAGE_UPLOADED_KEY,
                    new ImageUploadedEvent(UUID.randomUUID(), fileId, storagePath, detectedMimeType));
        } catch (Exception e) {
            log.warn("MQ 發送失敗（best-effort），不影響上傳結果: {}", storagePath, e);
        }
        /**
         * B4 / spec §3.1：回傳相對路徑而非 MinIO 直連網址，避免絕對網址把域名焊進 markdown 內文
         * （換域名時內文全破）。瀏覽器對 {@code /api/...} 以當前 origin 解析是 HTML 內建行為，
         * 不需要 render-time 改寫。實際內容由 {@code GET /api/v1/files/{id}/content} 端點
         * 判斷授權後由後端從物件儲存串流回瀏覽器（W5A：不再 302／presigned，物件儲存不對外）。
         */
        String url = "/api/v1/files/" + fileId + "/content";
        return new FileUploadResponse(fileId, url, width, height, (long) fileBytes.length, usageType);
    }

    /**
     * 刪除指定檔案
     *
     * <p>
     * <b>順序（RACE-16／PERF-28）</b>：(1) 載入 metadata 並做授權檢查；(2) 在<b>交易外</b>刪除 MinIO 原檔與縮圖
     * （縮圖路徑一律嘗試，不看 {@code hasThumbnail}——縮圖 consumer 可能在刪除競態中剛寫入但 flag 尚未寫入，見 DATA-14；
     * S3 的 DeleteObject 對不存在的物件視為成功）；(3) MinIO 都成功後，才用<b>短交易</b>刪 DB 列。
     * 本方法刻意<b>不標註 {@code @Transactional}</b>，否則外層交易會再次橫跨 MinIO 網路呼叫。
     * </p>
     *
     * <p>
     * 任一 MinIO 刪除失敗即拋 {@code STORAGE_ERROR}，DB 列<b>不刪</b>：API 回錯，帳號刪除連動等 consumer 路徑
     * 交給 MQ 重試或進 DLQ，因此不會產生孤兒物件。
     * <b>已知代價</b>：MinIO 已刪但 DB 刪除失敗時，metadata 會短暫指向不存在的物件；使用者重試刪除時 MinIO 端為
     * no-op、DB 列被刪掉，consumer 則由 MQ 重試達成相同結果。
     * </p>
     *
     * @param fileId      要刪除的檔案 UUID
     * @param requesterId 請求刪除的使用者 UUID
     * @param isAdmin     是否為管理員
     */
    @Override
    public void deleteFile(UUID fileId, UUID requesterId, boolean isAdmin) {
        FileMetadata metadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(FileErrorCode.FILE_NOT_FOUND));
        if (!isAdmin && !metadata.belongsTo(requesterId)) {
            throw new BusinessException(FileErrorCode.FILE_ACCESS_DENIED);
        }
        removeObjectOrThrow(metadata.getStoragePath());
        removeObjectOrThrow(buildThumbPath(metadata.getStoragePath()));
        transactionTemplate.executeWithoutResult(status -> fileMetadataRepository.deleteById(fileId));
    }

    /**
     * 刪除單一 MinIO 物件；失敗記 ERROR（含 storage path）並拋 {@code STORAGE_ERROR}。
     *
     * @param objectPath 物件鍵
     */
    private void removeObjectOrThrow(String objectPath) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectPath)
                            .build());
        } catch (Exception e) {
            log.error("MinIO 刪除失敗: {}", objectPath, e);
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

    /** 未提供分頁（unpaged）時的預設每頁筆數，與 Spring Data 的預設一致 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 分頁取得使用者上傳的檔案（最新在前）
     *
     * <p>AUTH-11（b）：先前收了 {@link Pageable} 卻忽略，一次載入該使用者全部檔案。現在真正套用
     * page／size（沿用 Spring {@code Pageable} 的 0 起算頁碼與回應形狀，避免改動前端契約）。
     * {@code size} 上限同 {@link PageQuery#MAX_SIZE}，超過一律拒絕（SEC-04：不靜默夾界）。
     * 排序固定為建立時間新到舊，不接受 client 指定的 sort 屬性。</p>
     *
     * @param uploaderId 使用者 UUID
     * @param pageable   分頁參數；unpaged 時取第一頁、預設筆數
     * @return 該頁的檔案元資料列表
     * @throws BusinessException size 超過 {@link PageQuery#MAX_SIZE}（{@code PARAM_VALID_ERROR}，HTTP 400）
     */
    @Override
    public List<FileMetadata> getUserFiles(UUID uploaderId, Pageable pageable) {
        int size = (pageable == null || pageable.isUnpaged()) ? DEFAULT_PAGE_SIZE : pageable.getPageSize();
        long offset = (pageable == null || pageable.isUnpaged()) ? 0L : pageable.getOffset();
        if (size < 1 || size > PageQuery.MAX_SIZE) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR,
                    "size 必須介於 1 與 " + PageQuery.MAX_SIZE + " 之間");
        }
        return fileMetadataRepository.findPageByUploaderId(uploaderId, size, offset);
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
     * 「未綁定 = 私有」，讀者端整篇破圖，正是 {@link FileService#bindToArticle} 完整替換語意的
     * 「權限殘留反向問題」。唯一例外是呼叫端<b>明確傳入空清單</b>：那是「清除本文章所有綁定」
     * 的合法用法，語意上不需要作者資訊，因此不受此前置檢查限制。
     * </p>
     *
     * <p>
     * 全程標註 {@link Transactional}：解綁與綁定是同一次「完整替換」的兩半，中途拋例外
     * 若各自獨立提交會留下半套綁定狀態（呼叫端 {@code FileArticleContentChangedConsumer}
     * 的冪等交易會 rollback 並交由 MQ 重試，但仍應避免留下半套狀態）。
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

        /*
         * PERF-23：固定次數的批次存取取代逐檔 findById + save（2N+1 次）：
         * 讀 = findByArticleUuid（1 次）+ findAllById（每 BatchedQuery.BATCH_SIZE 筆 1 次）；
         * 寫 = 單欄位 UPDATE（每批 1 次）。單欄位 UPDATE 同時避免整列 save 以舊值覆寫並發寫入的其他欄位。
         */
        Set<UUID> targetIdSet = new LinkedHashSet<>(targetIds);
        List<UUID> toUnbind = fileMetadataRepository.findByArticleUuid(articleUuid).stream()
                .map(FileMetadata::getId)
                .filter(id -> !targetIdSet.contains(id))
                .toList();
        updateInBatches(toUnbind, batch -> fileMetadataRepository.unbindFilesFromArticle(articleUuid, batch));

        if (targetIds.isEmpty()) {
            return;
        }

        final UUID articleAuthorUuid = authorUuid;
        List<FileMetadata> candidates = BatchedQuery.queryInBatches(targetIdSet,
                batch -> {
                    List<FileMetadata> found = new ArrayList<>();
                    fileMetadataRepository.findAllById(batch).forEach(found::add);
                    return found;
                });
        List<UUID> toBind = new ArrayList<>();
        for (FileMetadata metadata : candidates) {
            if (articleUuid.equals(metadata.getArticleUuid())) {
                continue;
            }
            if (!articleAuthorUuid.equals(metadata.getUploaderId())) {
                log.warn("bindToArticle：拒絕綁定非本文章作者上傳的檔案（可疑嘗試）。"
                                + "fileUuid={}, articleUuid={}, uploaderId={}, articleAuthorUuid={}",
                        metadata.getId(), articleUuid, metadata.getUploaderId(), articleAuthorUuid);
                continue;
            }
            toBind.add(metadata.getId());
        }
        updateInBatches(toBind, batch -> fileMetadataRepository.bindFilesToArticle(articleUuid, articleAuthorUuid, batch));
    }

    /**
     * 把 id 清單依 {@link BatchedQuery#BATCH_SIZE} 切批後逐批執行 UPDATE（空清單不執行，避免非法的 {@code IN ()}）。
     *
     * @param ids    要更新的檔案 UUID
     * @param update 單批更新動作
     */
    private void updateInBatches(List<UUID> ids, java.util.function.Consumer<List<UUID>> update) {
        for (int from = 0; from < ids.size(); from += BatchedQuery.BATCH_SIZE) {
            update.accept(ids.subList(from, Math.min(from + BatchedQuery.BATCH_SIZE, ids.size())));
        }
    }

    /**
     * 解析文章作者的 UUID（直接取自 {@link ArticleData#authorUuid()}，不再經 user 模組轉換）。
     *
     * <p>用於 {@link #bindToArticle} 與 {@link #resolveOwnedArticleUuid} 的擁有權比對。
     * 文章不存在、或 {@code authorUuid} 為 null（資料異常）時，皆回傳 null（fail-safe，由呼叫端決定如何處理）。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 作者 UUID；無法解析時為 null
     */
    private UUID resolveArticleAuthorUuid(UUID articleUuid) {
        return articleLookupFacade.findByUuid(articleUuid)
                .map(ArticleData::authorUuid)
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
        if (isPubliclyReadable(metadata)) {
            return true;
        }
        if (metadata.belongsTo(requesterId)) {
            return true;
        }
        return isAdmin;
    }

    /**
     * {@inheritDoc}
     *
     * <p>授權矩陣中不依賴請求者身分的部分（AVATAR、綁定公開文章）；未綁定或文章查無資料一律 false（fail-safe）。</p>
     */
    @Override
    public boolean isPubliclyReadable(FileMetadata metadata) {
        if (metadata.getUsageType() == UsageType.AVATAR) {
            return true;
        }
        if (metadata.getArticleUuid() != null) {
            Optional<ArticleData> boundArticle = articleLookupFacade.findByUuid(metadata.getArticleUuid());
            return boundArticle.isPresent() && ArticleStatus.isPubliclyVisible(boundArticle.get().status());
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * 使用 MinIO SDK {@code getObject} 取得串流，由呼叫端（{@code FileController}）邊讀邊寫入 response，
     * 不整檔載入記憶體。本方法不做任何權限判斷，呼叫端須先呼叫 {@link #canRead} 通過。
     * 物件儲存的主機只在後端內網可達，瀏覽器不會直接連線。
     * </p>
     */
    @Override
    public InputStream openContent(FileMetadata metadata) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(metadata.getStoragePath())
                            .build());
        } catch (Exception e) {
            log.error("開啟 MinIO 物件串流失敗: {}", metadata.getStoragePath(), e);
            throw new SystemException(CommonErrorCode.STORAGE_ERROR);
        }
    }

    /**
     * 使用 Apache Tika 偵測 MIME 類型（F-2：改用 byte[] 避免重複消耗 InputStream）
     *
     * <p>FILE-03：只看內容 magic bytes，不把使用者檔名當提示傳給 Tika。</p>
     *
     * @param fileBytes 檔案位元組陣列
     * @return 偵測到的 MIME 類型字串
     */
    private String detectMimeType(byte[] fileBytes) {
        try {
            return tika.detect(new ByteArrayInputStream(fileBytes));
        } catch (IOException e) {
            log.error("MIME 類型偵測失敗", e);
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
