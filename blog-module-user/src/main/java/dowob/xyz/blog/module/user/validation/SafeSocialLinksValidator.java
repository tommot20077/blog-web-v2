package dowob.xyz.blog.module.user.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.text.Normalizer;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link SafeSocialLinks} 的驗證器（SEC-10，A 案：value 為帳號名稱而非網址）
 *
 * <p>規則：</p>
 * <ol>
 *   <li>{@code null}／空字串：視為清除，通過。</li>
 *   <li>原始字串超過 {@value #MAX_RAW_LENGTH} 字元：解析前直接拒絕（避免為超大 payload 付解析成本）。</li>
 *   <li>必須是合法 JSON object；不允許尾隨內容與重複 key（避免解析器差異造成繞過）。</li>
 *   <li>項目數 ≤ {@value #MAX_ENTRIES}；key 符合 {@code [a-z0-9_]{1,32}}。</li>
 *   <li>value 必須是字串（JSON null、數字、布林、物件、陣列一律拒絕；「未填」請用空字串，
 *       這也是前端 {@code JSON.stringify} 的實際輸出）。</li>
 *   <li>非空 value：長度 ≤ {@value #MAX_VALUE_LENGTH}；不得含控制字元／空白／{@code / : \ < > "}
 *       （以原值與 NFKC 正規化後兩者檢查，擋全形 {@code ：／}）。因為不含 {@code :}，value 不可能構成任何 scheme。</li>
 *   <li>{@code @}：允許<b>單一前導</b> {@code @}（相容 Twitter 寫法，儲存時不剝除），其後必須有內容，
 *       且其餘位置不得再出現 {@code @}。</li>
 * </ol>
 *
 * @author Yuan
 * @version 1.0
 */
public class SafeSocialLinksValidator implements ConstraintValidator<SafeSocialLinks, String> {

    /** 原始 JSON 字串長度上限 */
    static final int MAX_RAW_LENGTH = 2000;

    /** 項目數上限 */
    static final int MAX_ENTRIES = 10;

    /** 單一 value 長度上限 */
    static final int MAX_VALUE_LENGTH = 100;

    /** key 格式 */
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");

    /** value 內不允許出現的字元（可構成 scheme、路徑、HTML 注入的字元） */
    private static final String FORBIDDEN_VALUE_CHARS = "/:\\<>\"";

    /** 嚴格解析器：禁止重複 key 與尾隨 token（thread-safe，可共用） */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * Bean Validation 入口。
     *
     * @param value   欄位值
     * @param context 驗證上下文
     * @return 通過回傳 true
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return isSafe(value);
    }

    /**
     * 判斷社群連結 JSON 是否合法。
     *
     * <p>設為 static 讓 service 層可在 controller 之外以同一份邏輯做防禦縱深檢查。</p>
     *
     * @param json 待檢查 JSON 字串
     * @return 合法（或為清除）回傳 true
     */
    public static boolean isSafe(String json) {
        if (json == null || json.isEmpty()) {
            return true;
        }
        if (json.length() > MAX_RAW_LENGTH) {
            return false;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return false;
        }
        if (root == null || !root.isObject() || root.size() > MAX_ENTRIES) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!KEY_PATTERN.matcher(field.getKey()).matches()) {
                return false;
            }
            JsonNode value = field.getValue();
            if (!value.isTextual() || !isSafeHandle(value.textValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判斷單一 value 是否為安全的帳號名稱。
     *
     * @param handle value 字串
     * @return 安全回傳 true；空字串代表未填，視為安全
     */
    private static boolean isSafeHandle(String handle) {
        if (handle.isEmpty()) {
            return true;
        }
        if (handle.length() > MAX_VALUE_LENGTH || SafeHttpUrlValidator.containsInvisibleOrControl(handle)) {
            return false;
        }
        String normalized = Normalizer.normalize(handle, Normalizer.Form.NFKC);
        if (containsForbiddenChar(handle) || containsForbiddenChar(normalized)) {
            return false;
        }
        String body = handle.startsWith("@") ? handle.substring(1) : handle;
        return !body.isEmpty() && body.indexOf('@') < 0;
    }

    /**
     * 是否含禁用字元。
     *
     * @param value 字串
     * @return 含有回傳 true
     */
    private static boolean containsForbiddenChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (FORBIDDEN_VALUE_CHARS.indexOf(value.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
