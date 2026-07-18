package dowob.xyz.blog.e2e.support;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * E2E 測試用資料建構工具
 */
public final class DataBuilder {

    private DataBuilder() {
    }

    public static Map<String, Object> article(String title, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("content", content);
        return map;
    }

    public static Map<String, Object> articleWithTags(String title, String content, List<String> tagNames) {
        Map<String, Object> map = article(title, content);
        map.put("tagNames", tagNames);
        return map;
    }

    public static Map<String, Object> category(String name, String slug) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("slug", slug);
        return map;
    }

    public static Map<String, Object> register(String email, String password,
                                                String username, String nickname) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("email", email);
        map.put("password", password);
        map.put("username", username);
        map.put("nickname", nickname);
        return map;
    }

    public static Map<String, Object> login(String identifier, String password) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("identifier", identifier);
        map.put("password", password);
        return map;
    }

    public static Map<String, Object> verifyEmail(String token) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("token", token);
        return map;
    }
}
