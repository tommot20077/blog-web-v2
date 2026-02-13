package dowob.xyz.blog.module.article.repository;

import dowob.xyz.blog.module.article.model.Article;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文章 Repository
 *
 * <p>
 * 提供文章基本 CRUD 操作，複雜查詢由 ArticleMapper 負責。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface ArticleRepository extends CrudRepository<Article, Long> {

    /**
     * 根據 UUID 查詢文章
     *
     * @param uuid 文章公開 UUID
     * @return 文章 Optional
     */
    Optional<Article> findByUuid(UUID uuid);

    /**
     * 根據作者 ID 查詢所有文章
     *
     * @param authorId 作者資料庫主鍵
     * @return 文章列表
     */
    List<Article> findByAuthorId(Long authorId);
}
