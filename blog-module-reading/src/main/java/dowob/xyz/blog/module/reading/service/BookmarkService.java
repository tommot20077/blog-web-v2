package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.reading.mapper.BookmarkMapper;
import dowob.xyz.blog.module.reading.model.UserBookmark;
import dowob.xyz.blog.module.reading.repository.UserBookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文章收藏 Service。Idempotent bookmark / unbookmark + batch 查詢。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class BookmarkService {

    private final UserBookmarkRepository repo;
    private final BookmarkMapper mapper;

    @Transactional
    public void bookmark(Long userId, Long articleId) {
        if (repo.findByUserIdAndArticleId(userId, articleId).isPresent()) {
            return;
        }
        UserBookmark bm = new UserBookmark();
        bm.setUserId(userId);
        bm.setArticleId(articleId);
        bm.setCreatedAt(LocalDateTime.now());
        repo.save(bm);
    }

    @Transactional
    public void unbookmark(Long userId, Long articleId) {
        if (repo.findByUserIdAndArticleId(userId, articleId).isEmpty()) {
            return;
        }
        repo.deleteByUserIdAndArticleId(userId, articleId);
    }

    public boolean isBookmarked(Long userId, Long articleId) {
        return repo.findByUserIdAndArticleId(userId, articleId).isPresent();
    }

    public Set<Long> batchIsBookmarked(Long userId, List<Long> articleIds) {
        if (userId == null || articleIds == null || articleIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(BatchedQuery.queryInBatches(articleIds,
                batch -> mapper.findBookmarkedArticleIdsByUser(userId, batch)));
    }

    /**
     * 我的全部收藏文章 id（最新優先，不分頁）。
     *
     * @param userId 使用者主鍵
     * @return 全部收藏文章主鍵
     */
    public List<Long> findAllMyBookmarkedArticleIds(Long userId) {
        return mapper.findAllMyBookmarkedArticleIds(userId);
    }
}
