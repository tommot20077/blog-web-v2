package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.reading.mapper.ArticleLikeMapper;
import dowob.xyz.blog.module.reading.model.ArticleLike;
import dowob.xyz.blog.module.reading.repository.ArticleLikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final ArticleLikeMapper articleLikeMapper;
    private final ArticleFacade articleFacade;

    /**
     * 按讚（idempotent）。
     *
     * <p>並發安全：先 fast-path 檢查存在跳過，否則 try save；若 save 撞 UNIQUE 約束（其他 tx 同時 insert），
     * 視為「已被別人按過」吞掉例外不再 increment，保持 idempotent 語意。</p>
     */
    @Transactional
    public void likeArticle(Long userId, Long articleId) {
        if (likeRepo.findByUserIdAndArticleId(userId, articleId).isPresent()) {
            return;
        }
        ArticleLike like = new ArticleLike();
        like.setUserId(userId);
        like.setArticleId(articleId);
        like.setCreatedAt(LocalDateTime.now());
        try {
            likeRepo.save(like);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(user_id, article_id) 撞了 — 已被並行 tx 按過，idempotent 返回
            return;
        }
        articleFacade.incrementLikeCount(articleId);
    }

    /**
     * 取消讚（idempotent）。
     *
     * <p>並發安全：依 delete 實際 affected rows 決定要不要 decrement，
     * 避免「先查存在但 delete 影響 0 rows」造成 like_count 漂移。</p>
     */
    @Transactional
    public void unlikeArticle(Long userId, Long articleId) {
        int affected = likeRepo.deleteByUserIdAndArticleId(userId, articleId);
        if (affected > 0) {
            articleFacade.decrementLikeCount(articleId);
        }
    }

    /**
     * 查詢使用者是否已對指定文章按讚。
     *
     * @param userId    使用者主鍵
     * @param articleId 文章主鍵
     * @return true 若已按讚
     */
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
        return new HashSet<>(BatchedQuery.queryInBatches(articleIds,
                batch -> articleLikeMapper.findLikedArticleIdsByUser(userId, batch)));
    }
}
