package dowob.xyz.blog.infrastructure.facade.dto;

import java.util.UUID;

/**
 * series 導覽（prev / next）用的極簡文章參照。
 *
 * <p>只含渲染一個導覽連結所需的三個欄位。刻意不重用 {@code ArticleData}
 * （缺 title / slug）或 {@code ArticleBasicInfo}（缺 title / slug，且帶用不到的 tagIds）。</p>
 *
 * @param uuid  文章公開 UUID
 * @param title 文章標題
 * @param slug  文章 URL slug
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleNavRef(UUID uuid, String title, String slug) {}
