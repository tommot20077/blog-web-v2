package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.event.TagInfo;

import java.util.List;
import java.util.UUID;

/**
 * 標籤 Facade 介面
 *
 * <p>
 * 跨模組標籤操作的統一入口，由 blog-module-tag 提供實作。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface TagFacade {

    /**
     * 批次查找或建立標籤
     *
     * @param tagNames 標籤名稱列表
     * @return 標籤資訊列表（含 UUID、名稱、slug）
     */
    List<TagInfo> findOrCreateTags(List<String> tagNames);

    /**
     * 同步文章標籤（先清除舊標籤，再建立新標籤）
     *
     * @param articleUuid 文章公開 UUID
     * @param tagIds      新標籤 UUID 列表
     */
    void syncArticleTags(UUID articleUuid, List<UUID> tagIds);

    /**
     * 刪除文章所有標籤關聯
     *
     * @param articleUuid 文章公開 UUID
     */
    void deleteArticleTags(UUID articleUuid);

    /**
     * 查詢文章當前綁定的所有標籤 UUID
     *
     * <p>供 version 模組建立快照時使用，避免 restore 時 tags = null 而清空所有標籤。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 標籤 UUID 列表（無關聯時回傳空列表，不會回 null）
     */
    List<UUID> findTagIdsByArticleUuid(UUID articleUuid);
}
