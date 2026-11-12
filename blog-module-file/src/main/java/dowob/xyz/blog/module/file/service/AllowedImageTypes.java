package dowob.xyz.blog.module.file.service;

import java.util.Map;
import java.util.Optional;

/**
 * 允許的圖片 MIME 與副檔名／縮圖輸出格式的<b>唯一白名單</b>（FILE-03）。
 *
 * <p>
 * storage key 的副檔名與縮圖 {@code outputFormat} 一律由「Tika 偵測出的 MIME」查此表取得，
 * 完全不採用使用者提供的檔名，避免 {@code x.php}、{@code a.png%00.exe} 這類內容進入物件鍵或傳給縮圖函式庫。
 * 此表的值同時是 Thumbnailator 認得的格式名稱。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class AllowedImageTypes {

    /** MIME → 副檔名（亦為縮圖輸出格式名稱） */
    private static final Map<String, String> MIME_TO_EXTENSION = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/gif", "gif",
            "image/webp", "webp");

    private AllowedImageTypes() {
    }

    /**
     * 由 MIME 取得白名單副檔名（同時作為縮圖輸出格式）。
     *
     * @param mimeType MIME 類型（可為 null）
     * @return 副檔名（不含點）；不在白名單時為 empty
     */
    public static Optional<String> extensionFor(String mimeType) {
        return Optional.ofNullable(mimeType).map(MIME_TO_EXTENSION::get);
    }
}
