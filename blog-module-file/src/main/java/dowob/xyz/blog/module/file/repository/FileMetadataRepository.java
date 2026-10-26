package dowob.xyz.blog.module.file.repository;

import dowob.xyz.blog.module.file.model.FileMetadata;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

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

    /**
     * 依上傳者 ID 查詢所有檔案，並依建立時間降序排列
     *
     * @param uploaderId 上傳者 UUID
     * @return 該上傳者的所有檔案元資料列表
     */
    List<FileMetadata> findByUploaderIdOrderByCreatedAtDesc(UUID uploaderId);

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
}
