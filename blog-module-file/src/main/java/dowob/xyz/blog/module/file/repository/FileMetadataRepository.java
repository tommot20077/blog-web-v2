package dowob.xyz.blog.module.file.repository;

import dowob.xyz.blog.module.file.model.FileMetadata;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 檔案元資料 Repository
 *
 * <p>
 * 提供 file_metadata 表的 CRUD 操作與自訂查詢方法。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface FileMetadataRepository extends CrudRepository<FileMetadata, UUID> {

    /** 配額檢查 advisory lock 的鍵前綴（以上傳者為鍵，不同使用者互不阻塞） */
    String QUOTA_LOCK_KEY_PREFIX = "file-quota:";

    /**
     * 取得以上傳者為鍵的交易層級 advisory lock（{@code pg_advisory_xact_lock}），交易結束時自動釋放
     *
     * <p>供 {@code uploadFile} 在交易內「重新加總配額 + 寫入 metadata」之前序列化同一使用者的並發上傳
     * （RACE-12）。必須在交易內呼叫；不同鍵（不同使用者）互不阻塞。{@code hashtext} 的碰撞只會造成
     * 不相干使用者多一次序列化，不影響正確性。</p>
     *
     * @param key 鎖鍵，格式為 {@link #QUOTA_LOCK_KEY_PREFIX} + 上傳者 UUID
     * @return 固定回傳 1（僅為讓查詢有純量結果可映射）
     */
    @Query("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) AS l")
    int acquireQuotaLock(@Param("key") String key);

    /**
     * 依上傳者 ID 查詢所有檔案，並依建立時間降序排列
     *
     * @param uploaderId 上傳者 UUID
     * @return 該上傳者的所有檔案元資料列表
     */
    List<FileMetadata> findByUploaderIdOrderByCreatedAtDesc(UUID uploaderId);

    /**
     * 分頁查詢上傳者的檔案（最新在前；以 id 作為同時間戳的穩定次序）
     *
     * <p>供 {@code getUserFiles} 真正套用分頁（AUTH-11 b），取代一次載入該使用者全部檔案。
     * 排序固定，不接受 client 指定的 sort 屬性。</p>
     *
     * @param uploaderId 上傳者 UUID
     * @param limit      每頁筆數
     * @param offset     略過筆數
     * @return 該頁的檔案元資料
     */
    @Query("SELECT * FROM file_metadata WHERE uploader_id = :uploaderId "
            + "ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset")
    List<FileMetadata> findPageByUploaderId(@Param("uploaderId") UUID uploaderId,
                                            @Param("limit") int limit, @Param("offset") long offset);

    /**
     * 計算指定上傳者已使用的儲存空間總量
     *
     * @param uploaderId 上傳者 UUID
     * @return 已使用空間（位元組），若無資料則返回 0
     */
    @Query("SELECT COALESCE(SUM(size), 0) FROM file_metadata WHERE uploader_id = :uploaderId")
    Long sumSizeByUploaderId(@Param("uploaderId") UUID uploaderId);

    /**
     * 依綁定的文章 UUID 查詢所有檔案
     *
     * <p>供 {@code bindToArticle} 重新綁定時，找出該文章目前已綁定、
     * 但可能不在本次新清單內（需解除綁定）的舊檔案。</p>
     *
     * @param articleUuid 文章 UUID
     * @return 已綁定此文章的檔案元資料列表
     */
    List<FileMetadata> findByArticleUuid(UUID articleUuid);

    /**
     * 只更新 {@code has_thumbnail} 旗標為 true，不動其他欄位
     *
     * <p>供 {@code ThumbnailConsumer} 使用。縮圖處理耗時（下載、縮圖、上傳），若改用
     * {@code findById} 後再 {@code save}，Spring Data JDBC 會把整列所有欄位一起 UPDATE，
     * 處理期間其他流程寫入的 {@code article_uuid}（{@code bindToArticle}）會被舊值蓋回 null；
     * 檔案若已被刪除，{@code save} 還會拋例外。單欄位 UPDATE 沒有這兩個問題：檔案已被刪除時
     * 影響 0 列，屬正常 no-op。</p>
     *
     * @param id 檔案 UUID
     * @return 受影響列數（檔案已不存在時為 0）
     */
    @Modifying
    @Query("UPDATE file_metadata SET has_thumbnail = TRUE WHERE id = :id")
    int markThumbnailGenerated(@Param("id") UUID id);

    /**
     * 批次綁定：把指定檔案的 {@code article_uuid} 設為該文章（單欄位 UPDATE，不覆寫其他欄位）
     *
     * <p>供 {@code bindToArticle}（PERF-23）取代逐檔 {@code findById + save}。單欄位 UPDATE 另有好處：
     * 不會像整列 {@code save} 那樣以舊值蓋掉並發寫入的 {@code has_thumbnail}。
     * 呼叫端須自行切批（{@code ids} 不可為空，且大小需在 bind parameter 安全範圍內）。</p>
     *
     * <p>縱深防護：SQL 另帶 {@code uploader_id = :uploaderId}（文章作者）。service 層已先比對過擁有權，
     * 這裡再在 UPDATE 本身確保「只會綁定作者自己的檔案」，即使讀取與寫入之間 uploader 被改動也綁不到別人的檔案。</p>
     *
     * @param articleUuid 要綁定的文章 UUID
     * @param uploaderId  文章作者 UUID（僅此上傳者的檔案會被綁定）
     * @param ids         要綁定的檔案 UUID（非空）
     * @return 受影響列數
     */
    @Modifying
    @Query("UPDATE file_metadata SET article_uuid = :articleUuid WHERE uploader_id = :uploaderId AND id IN (:ids)")
    int bindFilesToArticle(@Param("articleUuid") UUID articleUuid, @Param("uploaderId") UUID uploaderId,
                           @Param("ids") Collection<UUID> ids);

    /**
     * 批次解除綁定：把指定檔案的 {@code article_uuid} 設回 null，但僅限目前仍綁定在該文章者
     *
     * <p>多帶 {@code article_uuid = :articleUuid} 條件，避免讀取後、更新前檔案已被改綁到別篇文章時誤解除。</p>
     *
     * @param articleUuid 原綁定的文章 UUID
     * @param ids         要解除的檔案 UUID（非空）
     * @return 受影響列數
     */
    @Modifying
    @Query("UPDATE file_metadata SET article_uuid = NULL WHERE article_uuid = :articleUuid AND id IN (:ids)")
    int unbindFilesFromArticle(@Param("articleUuid") UUID articleUuid, @Param("ids") Collection<UUID> ids);

    /**
     * 以 keyset 分頁取得被檔案綁定的不重複文章 UUID（升冪），供每日孤兒校正使用（DATA-07）
     *
     * <p>排序與游標比較都由資料庫決定（PostgreSQL uuid 以位元組序比較），不可在 Java 端排序後當游標。</p>
     *
     * @param after 游標（不含）；null 表示從頭
     * @param limit 本頁筆數上限
     * @return 升冪、去重的文章 UUID；無則為空清單
     */
    @Query("SELECT DISTINCT article_uuid FROM file_metadata WHERE article_uuid IS NOT NULL "
            + "AND (CAST(:after AS uuid) IS NULL OR article_uuid > CAST(:after AS uuid)) "
            + "ORDER BY article_uuid LIMIT :limit")
    List<UUID> findDistinctArticleUuidsAfter(@Param("after") UUID after, @Param("limit") int limit);

    /**
     * 以 keyset 分頁（依 id 升冪）取得「建立早於 {@code createdBefore}、尚未綁定任何文章、且不是頭像」的檔案，
     * 供每日未綁定檔清理（D6）使用。
     *
     * <p>只回傳<b>候選</b>：是否真的可刪還要再經 article 模組確認未被作者的任何文章內文／封面引用。
     * 排序與游標比較由資料庫決定，{@code after} 請傳 nil UUID（{@code 00000000-0000-0000-0000-000000000000}，
     * 在 PostgreSQL uuid 排序中最小）表示從頭開始。</p>
     *
     * @param createdBefore 建立時間上界（不含）
     * @param after         游標（不含）；從頭開始傳 nil UUID
     * @param limit         本頁筆數上限
     * @return 候選檔案，id 升冪；無則為空清單
     */
    @Query("SELECT * FROM file_metadata WHERE article_uuid IS NULL AND usage_type <> 'AVATAR' "
            + "AND created_at < :createdBefore AND id > :after ORDER BY id LIMIT :limit")
    List<FileMetadata> findUnboundNonAvatarCreatedBefore(@Param("createdBefore") LocalDateTime createdBefore,
                                                          @Param("after") UUID after,
                                                          @Param("limit") int limit);
}
