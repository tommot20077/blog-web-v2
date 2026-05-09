package dowob.xyz.blog.module.reading.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * Reading Progress MyBatis Mapper：UPSERT 用。
 *
 * <p>PostgreSQL ON CONFLICT 語法保證 (user_id, article_id) UNIQUE 衝突時更新而非報錯。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface ReadingProgressMapper {

    /**
     * UPSERT user_reading_progress；衝突時更新進度與 last_heading_anchor。
     */
    @Update("""
            INSERT INTO user_reading_progress (user_id, article_id, progress, last_heading_anchor, updated_at)
            VALUES (#{userId}, #{articleId}, #{progress}, #{lastHeading}, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, article_id) DO UPDATE SET
              progress = EXCLUDED.progress,
              last_heading_anchor = EXCLUDED.last_heading_anchor,
              updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(@Param("userId") Long userId,
               @Param("articleId") Long articleId,
               @Param("progress") BigDecimal progress,
               @Param("lastHeading") String lastHeading);
}
