package dowob.xyz.blog.module.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@link SafeHttpUrl} 的驗證器（SEC-10）
 *
 * <p>判定規則（皆以 {@link java.net.URI} 解析結果判斷，不以 regex 拼湊協定）：</p>
 * <ol>
 *   <li>{@code null}／空字串：視為「清除欄位」，通過。</li>
 *   <li>長度超過上限：拒絕。</li>
 *   <li>含任何控制字元、空白（含 Unicode 空白如 NBSP）或不可見格式字元（如零寬空白）：拒絕。
 *       這同時擋掉前導／結尾空白與 {@code java\tscript:} 這類 tab／換行混淆。</li>
 *   <li>（選用）精確等於本站檔案內容路徑 {@code /api/v1/files/{uuid}/content}：通過。</li>
 *   <li>無法被 {@code URI} 解析、非絕對 URI（相對路徑、protocol-relative {@code //host}）、
 *       協定不是 http／https（不分大小寫）：拒絕。百分比編碼的協定（{@code %6A%61...:}）在 URI 語法中不構成
 *       scheme，因此同樣落在「無 scheme」而被拒絕。</li>
 *   <li>沒有 host（{@code http:///x}、{@code http:foo}）或帶 userinfo（{@code https://good.com@evil.com}
 *       這類釣魚寫法）：拒絕。</li>
 * </ol>
 *
 * @author Yuan
 * @version 1.0
 */
public class SafeHttpUrlValidator implements ConstraintValidator<SafeHttpUrl, String> {

    /** 本站檔案內容路徑（檔案上傳 API 回傳格式），必須完整比對 */
    private static final Pattern FILE_CONTENT_PATH = Pattern.compile(
            "/api/v1/files/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/content");

    /** 主機名稱（ASCII／punycode，label 以英數開頭結尾，允許連字號與底線） */
    private static final Pattern HOSTNAME = Pattern.compile(
            "[A-Za-z0-9_]([A-Za-z0-9_-]*[A-Za-z0-9_])?(\\.[A-Za-z0-9_]([A-Za-z0-9_-]*[A-Za-z0-9_])?)*");

    /** 長度上限 */
    private int maxLength;

    /** 是否允許本站檔案內容路徑 */
    private boolean allowFileContentPath;

    /**
     * 讀取註解參數。
     *
     * @param annotation 註解實例
     */
    @Override
    public void initialize(SafeHttpUrl annotation) {
        this.maxLength = annotation.maxLength();
        this.allowFileContentPath = annotation.allowFileContentPath();
    }

    /**
     * Bean Validation 入口。
     *
     * @param value   欄位值
     * @param context 驗證上下文
     * @return 通過回傳 true
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return isSafe(value, maxLength, allowFileContentPath);
    }

    /**
     * 判斷字串是否為安全的 http/https 網址。
     *
     * <p>設為 static 讓 service 層可在 controller 之外以同一份邏輯做防禦縱深檢查。</p>
     *
     * @param value                待檢查字串
     * @param maxLength            長度上限
     * @param allowFileContentPath 是否額外允許本站檔案內容路徑
     * @return 安全（或為清除）回傳 true
     */
    public static boolean isSafe(String value, int maxLength, boolean allowFileContentPath) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        if (value.length() > maxLength || containsInvisibleOrControl(value)) {
            return false;
        }
        if (allowFileContentPath && FILE_CONTENT_PATH.matcher(value).matches()) {
            return true;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(lower) && !"https".equals(lower)) {
            return false;
        }
        return hasValidHostWithoutUserInfo(uri);
    }

    /**
     * 是否含控制字元、空白、Unicode 空白或不可見格式字元。
     *
     * @param value 字串
     * @return 含有回傳 true
     */
    static boolean containsInvisibleOrControl(String value) {
        return value.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                || Character.isWhitespace(cp)
                || Character.isSpaceChar(cp)
                || Character.getType(cp) == Character.FORMAT);
    }

    /**
     * 檢查 host 存在且合法，且不帶 userinfo。
     *
     * <p>{@code java.net.URI} 對非 ASCII 主機（IDN）會退回 registry-based authority 而讓 {@code getHost()} 為
     * null，這裡改以 {@link IDN#toASCII(String)} 轉 punycode 後驗證，避免誤擋合法的國際化網域。</p>
     *
     * @param uri 已解析的 URI
     * @return 合法回傳 true
     */
    private static boolean hasValidHostWithoutUserInfo(URI uri) {
        if (uri.getRawUserInfo() != null) {
            return false;
        }
        String host = uri.getHost();
        if (host != null) {
            return !host.isEmpty();
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty() || authority.indexOf('@') >= 0 || authority.startsWith("[")) {
            return false;
        }
        String hostPart = authority;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            String port = authority.substring(colon + 1);
            if (!port.chars().allMatch(Character::isDigit)) {
                return false;
            }
            hostPart = authority.substring(0, colon);
        }
        try {
            return HOSTNAME.matcher(IDN.toASCII(hostPart)).matches();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
