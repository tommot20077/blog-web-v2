package dowob.xyz.blog.module.user.util;

/**
 * Token 版本號工具類
 *
 * <p>提供 token 版本號的純函數操作。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class TokenVersionUtils {

    private TokenVersionUtils() {
    }

    /**
     * 遞增 Token 版本號
     *
     * <p>解析 "v{n}" 格式的版本字串，遞增數字後回傳新版本。
     * 若格式不符，預設從 v1 開始。</p>
     *
     * @param currentVersion 當前版本號，例如 "v1"
     * @return 新的版本號，例如 "v2"
     */
    public static String incrementVersion(String currentVersion) {
        if (currentVersion == null || !currentVersion.startsWith("v")) {
            return "v1";
        }
        try {
            int num = Integer.parseInt(currentVersion.substring(1));
            return "v" + (num + 1);
        } catch (NumberFormatException e) {
            return "v1";
        }
    }
}
