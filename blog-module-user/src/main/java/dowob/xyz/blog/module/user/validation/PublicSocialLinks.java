package dowob.xyz.blog.module.user.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公開輸出用的社群連結寬鬆解析器
 *
 * <p>寫入端的嚴格規則在 {@link SafeSocialLinksValidator}（SEC-10，value 為<b>帳號名稱</b>而非網址）；
 * 本類負責<b>讀取端</b>：把資料庫裡已儲存的 JSON 轉成可公開輸出的 Map。
 * 既有資料不遷移，所以這裡不信任儲存值——解析失敗或不合格的項目一律略過，<b>永不拋例外</b>。</p>
 *
 * <p>規則（與寫入端共用同一份 key／value 判斷，不重寫第二份）：</p>
 * <ul>
 *   <li>輸入為 null、空白、超過 {@value SafeSocialLinksValidator#MAX_RAW_LENGTH} 字元、非合法 JSON 或非 JSON object：
 *       視為沒有任何連結。</li>
 *   <li>key 須符合 {@code [a-z0-9_]{1,32}}；value 須為非空字串且通過 {@link SafeSocialLinksValidator#isSafeHandle}
 *       （不含 {@code / : \ < > "}、空白、控制字元；至多單一前導 {@code @}）。</li>
 *   <li>至多輸出 {@value SafeSocialLinksValidator#MAX_ENTRIES} 筆合格項目（與寫入端上限一致）。</li>
 *   <li>{@code github}／{@code twitter}／{@code linkedin} 三個 key 恆存在，未填或不合格為 null；
 *       其他合格 key 照實輸出。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
public final class PublicSocialLinks {

    /** 恆存在於輸出的已知平台 key（契約：前端可直接依 key 取值） */
    private static final List<String> KNOWN_PLATFORMS = List.of("github", "twitter", "linkedin");

    /** 寬鬆解析器：不啟用重複 key 偵測，重複 key 以最後一個為準（舊資料可能由任何來源寫入） */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * 工具類別，不提供實例化。
     */
    private PublicSocialLinks() {
    }

    /**
     * 把儲存的社群連結 JSON 轉成可公開輸出的 Map。
     *
     * @param json {@code users.social_links} 的原始字串；可為 null
     * @return 已知三個平台 key 先出現（未填為 null），其後是其他合格 key；永不為 null、永不拋例外
     */
    public static Map<String, String> from(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        KNOWN_PLATFORMS.forEach(platform -> result.put(platform, null));
        if (json == null || json.isBlank() || json.length() > SafeSocialLinksValidator.MAX_RAW_LENGTH) {
            return result;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return result;
        }
        if (root == null || !root.isObject()) {
            return result;
        }
        int accepted = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext() && accepted < SafeSocialLinksValidator.MAX_ENTRIES) {
            Map.Entry<String, JsonNode> field = fields.next();
            String handle = acceptableHandle(field.getKey(), field.getValue());
            if (handle != null) {
                result.put(field.getKey(), handle);
                accepted++;
            }
        }
        return result;
    }

    /**
     * 判斷單一項目是否可公開輸出。
     *
     * @param key   欄位名稱
     * @param value 欄位值
     * @return 可輸出的帳號名稱；不合格或未填回傳 null
     */
    private static String acceptableHandle(String key, JsonNode value) {
        if (!SafeSocialLinksValidator.KEY_PATTERN.matcher(key).matches() || !value.isTextual()) {
            return null;
        }
        String handle = value.textValue();
        if (handle.isEmpty() || !SafeSocialLinksValidator.isSafeHandle(handle)) {
            return null;
        }
        return handle;
    }
}
