package dowob.xyz.blog.infrastructure.facade.dto;

import java.util.List;
import java.util.UUID;

/**
 * 文章基本資訊 DTO（用於推薦模組）
 *
 * <p>
 * 提供推薦演算法所需的文章基本識別資訊，
 * 包含標籤列表以支援同標籤推薦策略。
 * </p>
 *
 * @param uuid    文章公開 UUID
 * @param tagIds  文章所屬標籤 ID 列表
 * @author Yuan
 * @version 1.0
 */
public record ArticleBasicInfo(
        UUID uuid,
        List<UUID> tagIds
) {
}
