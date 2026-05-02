package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.module.version.model.UserPreference;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 user 的 effective version config（user_preferences override → application.yaml fallback）。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class PreferenceResolver {

    public static final String KEY_ENABLED = "version.auto.enabled";
    public static final String KEY_RETAIN = "version.auto.retain";
    public static final String KEY_INTERVAL_SECONDS = "version.auto.interval-seconds";
    public static final String KEY_DIFF_CHARS = "version.auto.diff-chars";

    private final UserPreferenceRepository repo;

    @Value("${version.auto.enabled:true}")
    private boolean defaultEnabled;
    @Value("${version.auto.retain:50}")
    private int defaultRetain;
    @Value("${version.auto.interval-seconds:60}")
    private int defaultIntervalSeconds;
    @Value("${version.auto.diff-chars:50}")
    private int defaultDiffChars;

    public AutoSnapshotConfig resolveForUser(Long userId) {
        Map<String, String> kv = toMap(repo.findByUserId(userId));
        return new AutoSnapshotConfig(
            getBool(kv, KEY_ENABLED,          defaultEnabled),
            getInt (kv, KEY_RETAIN,           defaultRetain),
            getInt (kv, KEY_INTERVAL_SECONDS, defaultIntervalSeconds),
            getInt (kv, KEY_DIFF_CHARS,       defaultDiffChars)
        );
    }

    /** 取得單筆 user override，未設則回 null（給 EffectiveConfigResponse source 標示用）*/
    public String getRawValue(Long userId, String prefKey) {
        return repo.findByUserIdAndPrefKey(userId, prefKey)
                .map(UserPreference::getPrefValue)
                .orElse(null);
    }

    private Map<String, String> toMap(List<UserPreference> prefs) {
        Map<String, String> map = new HashMap<>();
        for (UserPreference p : prefs) {
            map.put(p.getPrefKey(), p.getPrefValue());
        }
        return map;
    }

    private boolean getBool(Map<String, String> kv, String key, boolean fallback) {
        String v = kv.get(key);
        if (v == null) return fallback;
        return Boolean.parseBoolean(v);
    }

    private int getInt(Map<String, String> kv, String key, int fallback) {
        String v = kv.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { return fallback; }
    }
}
