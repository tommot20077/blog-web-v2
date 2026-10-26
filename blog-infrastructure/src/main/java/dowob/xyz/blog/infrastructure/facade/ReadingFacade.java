package dowob.xyz.blog.infrastructure.facade;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 閱讀互動模組跨模組查詢 Facade 介面
 *
 * <p>
 * 供 article 模組（ArticleQueryService）查詢收藏狀態與閱讀進度，
 * 避免 blog-module-article ↔ blog-module-reading 循環依賴。
 * 實作由 blog-module-reading 提供，透過 Spring DI 注入。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface ReadingFacade {

    /**
     * 批次查詢指定使用者已收藏的文章 UUID 集合。
     *
     * <p>userUuid 為 null 時回傳空集合（未登入）。M2-b 起跨模組識別一律為 UUID。</p>
     *
     * @param userUuid     使用者公開 UUID（可為 null）
     * @param articleUuids 文章公開 UUID 列表
     * @return 已收藏的文章 UUID 集合
     */
    Set<UUID> batchIsBookmarked(UUID userUuid, List<UUID> articleUuids);

    /**
     * 查詢單篇文章是否已被指定使用者收藏。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return 已收藏為 true，否則 false
     */
    boolean isBookmarked(UUID userUuid, UUID articleUuid);

    /**
     * 批次查詢指定使用者的閱讀進度。
     *
     * <p>userUuid 為 null 時回傳空 Map（未登入）。進度值為 0.00 ~ 1.00。M2-c 起跨模組識別一律為 UUID。</p>
     *
     * @param userUuid     使用者公開 UUID（可為 null）
     * @param articleUuids 文章公開 UUID 列表
     * @return articleUuid → progress 對應 Map（無紀錄則不含該 key）
     */
    Map<UUID, BigDecimal> batchGetProgress(UUID userUuid, List<UUID> articleUuids);

    /**
     * 查詢單篇文章的閱讀進度。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return 閱讀進度（0.00 ~ 1.00），無紀錄時回傳 null
     */
    BigDecimal getProgress(UUID userUuid, UUID articleUuid);

    /**
     * 批次查詢使用者按讚過的文章 UUID。
     *
     * <p>userUuid 為 null 時回傳空集合（未登入）。M2-b 起跨模組識別一律為 UUID。</p>
     *
     * @param userUuid     使用者公開 UUID（null 代表未登入，回 emptySet）
     * @param articleUuids 候選文章公開 UUID 列表
     * @return 已按讚的文章 UUID 集合
     */
    Set<UUID> batchIsLiked(UUID userUuid, List<UUID> articleUuids);

    /**
     * 查詢單篇文章是否被當前使用者按讚。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return 已按讚為 true，否則 false
     */
    boolean isLiked(UUID userUuid, UUID articleUuid);
}
