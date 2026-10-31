package dowob.xyz.blog.module.tag.repository;

import dowob.xyz.blog.module.tag.model.ArticleTag;
import dowob.xyz.blog.module.tag.model.Tag;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 文章標籤關聯資料存取介面
 *
 * <p>
 * 基於 Spring Data JDBC，使用 {@code @Query} 方法操作 {@code article_tags} 中間表，
 * 提供文章-標籤關聯的查詢、新增與刪除功能。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface ArticleTagRepository extends Repository<ArticleTag, UUID> {

    /**
     * 依文章 ID 查詢其所有標籤
     *
     * @param articleId 文章 ID
     * @return 標籤列表
     */
    @Query("SELECT t.* FROM tags t JOIN article_tags at2 ON t.id = at2.tag_id WHERE at2.article_id = :articleId")
    List<Tag> findTagsByArticleId(@Param("articleId") UUID articleId);

    /**
     * 刪除指定文章與標籤的關聯
     *
     * @param articleId 文章 ID
     * @param tagId     標籤 ID
     */
    @Modifying
    @Query("DELETE FROM article_tags WHERE article_id = :articleId AND tag_id = :tagId")
    void deleteByArticleIdAndTagId(@Param("articleId") UUID articleId, @Param("tagId") UUID tagId);

    /**
     * 新增文章與標籤的關聯
     *
     * @param articleId 文章 ID
     * @param tagId     標籤 ID
     */
    @Modifying
    @Query("INSERT INTO article_tags (article_id, tag_id) VALUES (:articleId, :tagId)")
    void save(@Param("articleId") UUID articleId, @Param("tagId") UUID tagId);

    /**
     * 計算指定標籤被文章使用的次數（用於刪除前 FK 檢查）
     *
     * @param tagId 標籤 ID
     * @return 關聯的文章數量
     */
    @Query("SELECT COUNT(*) FROM article_tags WHERE tag_id = :tagId")
    int countByTagId(@Param("tagId") UUID tagId);

    /**
     * 刪除指定文章的全部標籤關聯（文章刪除連動用）。
     *
     * <p>天然冪等：該文章已無關聯時刪除 0 筆，不會出錯。只刪 {@code article_tags} 的列，
     * 不動 {@code tags}（含 {@code usage_count}；與原 FK 的 {@code ON DELETE CASCADE} 行為一致）。</p>
     *
     * @param articleId 文章 UUID
     * @return 刪除的關聯筆數
     */
    @Modifying
    @Query("DELETE FROM article_tags WHERE article_id = :articleId")
    int deleteByArticleId(@Param("articleId") UUID articleId);

    /**
     * 以 keyset 分頁取得 {@code article_tags.article_id} 中不重複的文章 UUID（孤兒校正用）。
     *
     * <p>排序與比較都由資料庫的 uuid 型別決定（位元組無號比較），不可改在 Java 端排序，
     * 見 {@code OrphanReconciliationJob#fetchReferencedUuids} 的說明。</p>
     *
     * @param after 游標（不含）；null 表示從頭開始
     * @param limit 本頁筆數上限
     * @return 升冪、去重的文章 UUID
     */
    default List<UUID> findReferencedArticleIdsAfter(UUID after, int limit) {
        return after == null
                ? findReferencedArticleIdsFromStart(limit)
                : findReferencedArticleIdsAfterCursor(after, limit);
    }

    /**
     * {@link #findReferencedArticleIdsAfter} 的「從頭開始」查詢（內部使用）。
     *
     * @param limit 本頁筆數上限
     * @return 升冪、去重的文章 UUID
     */
    @Query("SELECT DISTINCT article_id FROM article_tags ORDER BY article_id LIMIT :limit")
    List<UUID> findReferencedArticleIdsFromStart(@Param("limit") int limit);

    /**
     * {@link #findReferencedArticleIdsAfter} 的「游標之後」查詢（內部使用）。
     *
     * @param after 游標（不含）
     * @param limit 本頁筆數上限
     * @return 升冪、去重的文章 UUID
     */
    @Query("SELECT DISTINCT article_id FROM article_tags WHERE article_id > :after ORDER BY article_id LIMIT :limit")
    List<UUID> findReferencedArticleIdsAfterCursor(@Param("after") UUID after, @Param("limit") int limit);

    /**
     * 刪除多篇文章的全部標籤關聯（孤兒校正用）。呼叫端須保證集合非空，避免產生 {@code IN ()} 語法錯誤。
     *
     * @param articleIds 文章 UUID，至少一筆
     * @return 刪除的關聯筆數
     */
    @Modifying
    @Query("DELETE FROM article_tags WHERE article_id IN (:articleIds)")
    int deleteByArticleIds(@Param("articleIds") Collection<UUID> articleIds);
}
