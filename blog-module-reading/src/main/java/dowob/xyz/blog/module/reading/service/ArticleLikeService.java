package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.reading.mapper.ArticleLikeMapper;
import dowob.xyz.blog.module.reading.repository.ArticleLikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文章按讚 Service。
 *
 * <p>提供 idempotent like / unlike 操作，並維護 articles.like_count 反正規化欄位。
 * 跨模組識別（使用者、文章）一律為 UUID；並發安全由資料庫約束兜底，
 * 而非「先檢查存在性 → 再操作」。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Service
@RequiredArgsConstructor
public class ArticleLikeService {

    private final ArticleLikeRepository likeRepo;
    private final ArticleLikeMapper articleLikeMapper;
    private final ArticleFacade articleFacade;

    /**
     * 按讚（idempotent，並發安全）。
     *
     * <p>以單一 {@code INSERT ... ON CONFLICT (user_uuid, article_uuid) DO NOTHING} 寫入，依 affected rows 決定
     * 是否 {@code like_count + 1}：回傳 1 才加，0 代表先前（或並發中對方）已按過，直接視為成功。
     * 不再先 find 再 save、也不再 catch {@code DataIntegrityViolationException}——後者在 Postgres 會讓
     * 當前交易進入 aborted 狀態並被標成 rollback-only，雙擊落敗者最後得到 500（RACE-01）。</p>
     *
     * <p>呼叫端（controller）須先確認文章存在；本方法不檢查。</p>
     *
     * @param userUuid    按讚者公開 UUID
     * @param articleUuid 文章公開 UUID
     */
    @Transactional
    public void likeArticle(UUID userUuid, UUID articleUuid) {
        int inserted = articleLikeMapper.insertIgnoreConflict(userUuid, articleUuid);
        if (inserted == 1) {
            articleFacade.incrementLikeCount(articleUuid);
        }
    }

    /**
     * 取消讚（idempotent）。
     *
     * <p>並發安全：依 delete 實際 affected rows 決定要不要 decrement，
     * 避免「先查存在但 delete 影響 0 rows」造成 like_count 漂移。</p>
     *
     * @param userUuid    取消讚的使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     */
    @Transactional
    public void unlikeArticle(UUID userUuid, UUID articleUuid) {
        int affected = likeRepo.deleteByUserUuidAndArticleUuid(userUuid, articleUuid);
        if (affected > 0) {
            articleFacade.decrementLikeCount(articleUuid);
        }
    }

    /**
     * 查詢使用者是否已對指定文章按讚。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return true 若已按讚
     */
    public boolean isLiked(UUID userUuid, UUID articleUuid) {
        return likeRepo.findByUserUuidAndArticleUuid(userUuid, articleUuid).isPresent();
    }

    /**
     * 批次查詢使用者已按讚的文章 UUID（用於列表頁避免 N+1）。
     *
     * @param userUuid     當前使用者公開 UUID，null 代表未登入
     * @param articleUuids 要查詢的文章公開 UUID 列表
     * @return 已按讚的文章 UUID 集合；未登入或空輸入回傳 emptySet
     */
    public Set<UUID> batchIsLiked(UUID userUuid, List<UUID> articleUuids) {
        if (userUuid == null || articleUuids == null || articleUuids.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(BatchedQuery.queryInBatches(articleUuids,
                batch -> articleLikeMapper.findLikedArticleUuidsByUser(userUuid, batch)));
    }
}
