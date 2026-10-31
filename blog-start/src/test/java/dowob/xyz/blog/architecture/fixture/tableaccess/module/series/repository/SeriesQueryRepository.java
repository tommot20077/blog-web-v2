package dowob.xyz.blog.architecture.fixture.tableaccess.module.series.repository;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;

/**
 * CrossModuleBoundaryTest 的反向驗證 fixture——<b>含故意違規</b>：
 * Spring Data JDBC 的 {@code @Query}（舊守衛完全沒掃）。
 *
 * @author Yuan
 * @version 1.0
 */
public interface SeriesQueryRepository {

    /** 違規：@Query 讀 users。 */
    @Query("SELECT u.nickname FROM users u")
    String readUsers();

    /** 違規：@Modifying @Query 刪 articles。 */
    @Modifying
    @Query("DELETE FROM articles WHERE series_id = :seriesId")
    int deleteArticles();

    /** 合規：@Query 讀自己的 series。 */
    @Query("SELECT * FROM series")
    String readOwn();
}
