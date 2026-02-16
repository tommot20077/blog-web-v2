package dowob.xyz.blog.module.tag.util;

/**
 * Slug 生成工具類
 *
 * <p>
 * 提供靜態方法，將任意字串轉換為 URL 友善的 Slug 格式。
 * 支援中文字元（保留 Unicode CJK 區塊），將其他特殊字元替換為連字號。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public class SlugUtils {

    /**
     * 私有建構子，防止工具類被實例化
     */
    private SlugUtils() {
    }

    /**
     * 將輸入字串轉換為 Slug 格式
     *
     * <p>
     * 轉換步驟：
     * <ol>
     *   <li>轉換為小寫</li>
     *   <li>將非字母數字及非中文字元替換為連字號</li>
     *   <li>移除首尾多餘的連字號</li>
     * </ol>
     * </p>
     *
     * @param input 原始輸入字串
     * @return Slug 格式字串
     */
    public static String generateSlug(String input) {
        return input.toLowerCase()
                .replaceAll("[^a-z0-9\u4e00-\u9fff]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
