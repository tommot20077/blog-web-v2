package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.version.exception.VersionErrorCode;
import dowob.xyz.blog.module.version.mapper.UserPreferenceMapper;
import dowob.xyz.blog.module.version.model.UserPreference;
import dowob.xyz.blog.module.version.model.dto.request.UpdatePreferenceRequest;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.model.dto.response.EffectiveConfigResponse;
import dowob.xyz.blog.module.version.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final UserPreferenceMapper userPreferenceMapper;

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

    /**
     * 更新 user preference（partial update），只更新非 null 欄位。
     * Service 層額外 range validation 作為 defense-in-depth。
     */
    @Transactional
    public EffectiveConfigResponse updatePreferences(Long userId, UpdatePreferenceRequest req) {
        if (req.getEnabled() != null) {
            userPreferenceMapper.upsert(userId, KEY_ENABLED, req.getEnabled().toString());
        }
        if (req.getRetain() != null) {
            validateRange(req.getRetain(), 1, 300);
            userPreferenceMapper.upsert(userId, KEY_RETAIN, req.getRetain().toString());
        }
        if (req.getIntervalSeconds() != null) {
            validateRange(req.getIntervalSeconds(), 10, 600);
            userPreferenceMapper.upsert(userId, KEY_INTERVAL_SECONDS, req.getIntervalSeconds().toString());
        }
        if (req.getDiffChars() != null) {
            validateRange(req.getDiffChars(), 0, 5000);
            userPreferenceMapper.upsert(userId, KEY_DIFF_CHARS, req.getDiffChars().toString());
        }
        return getEffectiveResponse(userId);
    }

    /**
     * 重置指定 key 為系統預設（刪除 user override）。
     */
    @Transactional
    public EffectiveConfigResponse resetKey(Long userId, String prefKey) {
        repo.deleteByUserIdAndPrefKey(userId, prefKey);
        return getEffectiveResponse(userId);
    }

    /**
     * 取得 user 的 effective config response（含 source 標示）。
     */
    @Transactional(readOnly = true)
    public EffectiveConfigResponse getEffectiveResponse(Long userId) {
        AutoSnapshotConfig effective = resolveForUser(userId);
        EffectiveConfigResponse r = new EffectiveConfigResponse();
        r.setEnabled(buildField(userId, KEY_ENABLED, effective.enabled()));
        r.setRetain(buildField(userId, KEY_RETAIN, effective.retain()));
        r.setIntervalSeconds(buildField(userId, KEY_INTERVAL_SECONDS, effective.intervalSeconds()));
        r.setDiffChars(buildField(userId, KEY_DIFF_CHARS, effective.diffChars()));
        return r;
    }

    private void validateRange(int value, int min, int max) {
        if (value < min || value > max) {
            throw new BusinessException(VersionErrorCode.PREFERENCE_INVALID);
        }
    }

    private <T> EffectiveConfigResponse.Field<T> buildField(Long userId, String key, T value) {
        String source = getRawValue(userId, key) != null ? "user" : "system";
        return new EffectiveConfigResponse.Field<>(value, source);
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
