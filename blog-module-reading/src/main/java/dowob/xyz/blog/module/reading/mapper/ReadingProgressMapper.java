package dowob.xyz.blog.module.reading.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Reading Progress MyBatis Mapper：單調 UPSERT 用。
 *
 * <p>PostgreSQL ON CONFLICT 語法保證 (user_uuid, article_uuid) UNIQUE 衝突時更新而非報錯。</p>
 *
 * <p><b>單調守衛</b>：每筆寫入都帶「使用者送出該進度的時間」{@code updatedAt}（JVM 時鐘的 epoch ms 轉換而來），
 * 衝突時僅在 {@code user_reading_progress.updated_at <= EXCLUDED.updated_at} 才覆寫，
 * 因此較舊的寫入（例如另一個 flusher 手上的舊值、與完讀路徑交錯的舊 flush）不會讓 DB 回退；相等時允許覆寫。
 * 單調依「時間」而非「進度大小」：使用者由 0.97 往回捲到 0.2 的較新寫入仍會生效。</p>
 *
 * <p><b>語意</b>：{@code updated_at} 是「使用者送出該進度的時間（JVM 時鐘）」，不再是 flush 寫入 DB 的時間（DB 時鐘）。</p>
 *
 * <p><b>時區假設</b>：JVM 與 DB 使用同一時區——{@code updated_at} 為 {@code timestamp without time zone}，
 * 傳入的 {@link LocalDateTime} 不做任何時區轉換直接寫入；呼叫端以 {@code ZoneId.systemDefault()} 在 epoch ms
 * 與 {@link LocalDateTime} 之間轉換，讀回時同樣以系統時區還原。</p>
 *
 * @author Yuan
 * @version 3.0
 */
@Mapper
public interface ReadingProgressMapper {

    /**
     * 單調 UPSERT user_reading_progress；衝突時僅在現有 {@code updated_at} 不比 {@code updatedAt} 新時，
     * 才更新進度、last_heading_anchor 與 updated_at。
     *
     * @param userUuid    讀者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param progress    進度
     * @param lastHeading 最後閱讀的標題錨點（可為 null）
     * @param updatedAt   使用者送出該進度的時間（版本；JVM 時鐘，與 DB 同時區）
     * @return 影響列數：1 代表新增或覆寫；0 代表現有列較新、被單調守衛擋下
     */
    @Update("""
            INSERT INTO user_reading_progress (user_uuid, article_uuid, progress, last_heading_anchor, updated_at)
            VALUES (#{userUuid}::uuid, #{articleUuid}::uuid, #{progress}, #{lastHeading}, #{updatedAt})
            ON CONFLICT (user_uuid, article_uuid) DO UPDATE SET
              progress = EXCLUDED.progress,
              last_heading_anchor = EXCLUDED.last_heading_anchor,
              updated_at = EXCLUDED.updated_at
            WHERE user_reading_progress.updated_at <= EXCLUDED.updated_at
            """)
    int upsert(@Param("userUuid") UUID userUuid,
               @Param("articleUuid") UUID articleUuid,
               @Param("progress") BigDecimal progress,
               @Param("lastHeading") String lastHeading,
               @Param("updatedAt") LocalDateTime updatedAt);
}
