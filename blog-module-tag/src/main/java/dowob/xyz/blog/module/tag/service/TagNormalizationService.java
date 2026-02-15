package dowob.xyz.blog.module.tag.service;

import dowob.xyz.blog.module.tag.model.Tag;

/**
 * 標籤正規化服務介面
 *
 * <p>
 * 定義標籤名稱正規化與查找或建立標籤的合約，
 * 實作類負責繁簡轉換、Slug 生成及 Redis 自動補全快取維護。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface TagNormalizationService {

    /**
     * 查找現有標籤或建立新標籤
     *
     * <p>
     * 對 rawName 執行正規化後，在資料庫中查找對應 Slug 的標籤；
     * 若不存在則建立新標籤，並將 Slug 加入 Redis 自動補全集合。
     * </p>
     *
     * @param rawName 原始標籤名稱
     * @return 現有或新建的標籤實體
     */
    Tag findOrCreate(String rawName);

    /**
     * 正規化標籤名稱
     *
     * <p>
     * 執行修剪、轉小寫、繁體轉簡體等正規化處理。
     * </p>
     *
     * @param rawName 原始標籤名稱
     * @return 正規化後的名稱
     */
    String normalize(String rawName);
}
