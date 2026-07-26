package dowob.xyz.blog.module.file.repository;

import dowob.xyz.blog.module.file.model.FileMetadata;
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
}
