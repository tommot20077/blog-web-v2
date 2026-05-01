package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.ArticleLike;
import dowob.xyz.blog.module.article.repository.ArticleLikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文章按讚 Service。
 *
 * <p>提供 idempotent like / unlike 操作，並維護 articles.like_count 反正規化欄位。
 * 採用「先檢查存在性 → 再操作」模式，DB UNIQUE 約束兜底。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class ArticleLikeService {

    private final ArticleLikeRepository likeRepo;
    private final ArticleMapper articleMapper;
    private final ArticleService articleService;

    /** 按讚（idempotent）。 */
    @Transactional
    public void likeArticle(Long userId, Long articleId) {
        if (likeRepo.findByUserIdAndArticleId(userId, articleId).isPresent()) {
            return;
        }
        ArticleLike like = new ArticleLike();
        like.setUserId(userId);
        like.setArticleId(articleId);
        like.setCreatedAt(LocalDateTime.now());
        likeRepo.save(like);
        articleService.incrementLikeCount(articleId);
    }

    /** 取消讚（idempotent）。 */
    @Transactional
    public void unlikeArticle(Long userId, Long articleId) {
        if (likeRepo.findByUserIdAndArticleId(userId, articleId).isEmpty()) {
            return;
        }
        likeRepo.deleteByUserIdAndArticleId(userId, articleId);
        articleService.decrementLikeCount(articleId);
    }

    public boolean isLiked(Long userId, Long articleId) {
        return likeRepo.findByUserIdAndArticleId(userId, articleId).isPresent();
    }

    /**
     * 批次查詢使用者已按讚的 articleId（用於列表頁避免 N+1）。
     *
     * @param userId     當前使用者，null 代表未登入
     * @param articleIds 要查詢的文章 PK 列表
     * @return 已按讚的 articleId 集合；未登入或空輸入回傳 emptySet
     */
    public Set<Long> batchIsLiked(Long userId, List<Long> articleIds) {
        if (userId == null || articleIds == null || articleIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(articleMapper.findLikedArticleIdsByUser(userId, articleIds));
    }
}
