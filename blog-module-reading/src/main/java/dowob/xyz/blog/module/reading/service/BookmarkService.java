package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.reading.mapper.BookmarkMapper;
import dowob.xyz.blog.module.reading.repository.UserBookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文章收藏 Service。Idempotent bookmark / unbookmark + batch 查詢。
 *
 * <p>跨模組識別（使用者、文章）一律為 UUID；並發安全由資料庫約束兜底。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Service
@RequiredArgsConstructor
public class BookmarkService {

    private final UserBookmarkRepository repo;
    private final BookmarkMapper mapper;

    /**
     * 收藏（idempotent，並發安全）。
     *
     * <p>以單一 {@code INSERT ... ON CONFLICT (user_uuid, article_uuid) DO NOTHING} 寫入：
     * 雙擊時落敗者影響 0 列，仍視為成功，不再因 UNIQUE 衝突而 500（RACE-04）。
     * 收藏沒有反正規化計數，故不依回傳值做後續動作。呼叫端（controller）須先確認文章存在。</p>
     *
     * @param userUuid    收藏者公開 UUID
     * @param articleUuid 文章公開 UUID
     */
    @Transactional
    public void bookmark(UUID userUuid, UUID articleUuid) {
        mapper.insertIgnoreConflict(userUuid, articleUuid);
    }

    /**
     * 取消收藏（idempotent）：單一 DELETE，不需先查；本來就沒收藏時影響 0 列，視為成功。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     */
    @Transactional
    public void unbookmark(UUID userUuid, UUID articleUuid) {
        repo.deleteByUserUuidAndArticleUuid(userUuid, articleUuid);
    }

    /**
     * 查詢使用者是否已收藏指定文章。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return true 若已收藏
     */
    public boolean isBookmarked(UUID userUuid, UUID articleUuid) {
        return repo.findByUserUuidAndArticleUuid(userUuid, articleUuid).isPresent();
    }

    /**
     * 批次查詢使用者已收藏的文章 UUID（用於列表頁避免 N+1）。
     *
     * @param userUuid     當前使用者公開 UUID，null 代表未登入
     * @param articleUuids 要查詢的文章公開 UUID 列表
     * @return 已收藏的文章 UUID 集合；未登入或空輸入回傳 emptySet
     */
    public Set<UUID> batchIsBookmarked(UUID userUuid, List<UUID> articleUuids) {
        if (userUuid == null || articleUuids == null || articleUuids.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(BatchedQuery.queryInBatches(articleUuids,
                batch -> mapper.findBookmarkedArticleUuidsByUser(userUuid, batch)));
    }

    /**
     * 我的全部收藏文章 UUID（最新優先，不分頁）。
     *
     * @param userUuid 使用者公開 UUID
     * @return 全部收藏文章 UUID，依收藏時間新到舊
     */
    public List<UUID> findAllMyBookmarkedArticleUuids(UUID userUuid) {
        return mapper.findAllMyBookmarkedArticleUuids(userUuid);
    }
}
