package dowob.xyz.blog.module.file.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 檔案元資料實體
 *
 * <p>
 * 對應資料庫 file_metadata 表，儲存上傳檔案的完整元資料。
 * 實作 {@link Persistable} 以確保手動設定 {@link #id} 時，
 * Spring Data JDBC 仍能正確識別新實體並執行 INSERT 而非 UPDATE。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Table("file_metadata")
public class FileMetadata implements Persistable<UUID> {

    /**
     * 資料庫主鍵（UUID）
     */
    @Id
    private UUID id;

    /**
     * 是否為新實體（用於 Spring Data JDBC Persistable 判斷）。
     * 手動設定 ID 後需設為 true，以強制執行 INSERT 而非 UPDATE。
     */
    @Transient
    @JsonIgnore
    private boolean newEntity = false;

    /**
     * 原始檔案名稱
     */
    @Column("original_name")
    private String originalName;

    /**
     * MinIO 儲存路徑
     */
    @Column("storage_path")
    private String storagePath;

    /**
     * MIME 類型（如 image/jpeg）
     */
    @Column("content_type")
    private String contentType;

    /**
     * 檔案大小（位元組）
     */
    private Long size;

    /**
     * 圖片寬度（像素），非圖片時為 null
     */
    private Integer width;

    /**
     * 圖片高度（像素），非圖片時為 null
     */
    private Integer height;

    /**
     * 檔案用途類型
     */
    @Column("usage_type")
    private UsageType usageType;

    /**
     * 是否已產生縮圖
     */
    @Column("has_thumbnail")
    private Boolean hasThumbnail = false;

    /**
     * 上傳者的 UUID
     */
    @Column("uploader_id")
    private UUID uploaderId;

    /**
     * 建立時間
     */
    @Column("created_at")
    private LocalDateTime createdAt;

    /**
     * 綁定的文章 UUID（nullable，對應 articles.uuid，無 FK）
     *
     * <p>
     * null 代表尚未綁定任何文章，屬 fail-safe 預設：僅上傳者與 ADMIN 可讀，
     * 避免新文章尚未儲存時上傳的圖片意外對外公開（見 V20 migration 註解）。
     * </p>
     */
    @Column("article_uuid")
    private UUID articleUuid;

    /**
     * 判斷此實體是否為新實體（尚未寫入資料庫）。
     * Spring Data JDBC 依此決定執行 INSERT 或 UPDATE。
     *
     * @return 若為新實體則返回 true，否則返回 false
     */
    @Override
    public boolean isNew() {
        return newEntity;
    }

    /**
     * 判斷此檔案是否屬於指定使用者
     *
     * @param userId 使用者 UUID
     * @return 若上傳者 ID 與傳入 userId 相同則返回 true，否則返回 false
     */
    public boolean belongsTo(UUID userId) {
        if (userId == null) {
            return false;
        }
        return uploaderId.equals(userId);
    }
}
