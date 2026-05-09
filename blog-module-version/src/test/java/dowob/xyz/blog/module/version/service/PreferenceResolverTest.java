package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.module.version.mapper.UserPreferenceMapper;
import dowob.xyz.blog.module.version.model.UserPreference;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.UserPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreferenceResolverTest {

    @Mock private UserPreferenceRepository repo;
    @Mock private UserPreferenceMapper userPreferenceMapper;
    @InjectMocks private PreferenceResolver resolver;

    private final Long userId = 1L;

    @BeforeEach
    void setupDefaults() {
        ReflectionTestUtils.setField(resolver, "defaultEnabled", true);
        ReflectionTestUtils.setField(resolver, "defaultRetain", 50);
        ReflectionTestUtils.setField(resolver, "defaultIntervalSeconds", 60);
        ReflectionTestUtils.setField(resolver, "defaultDiffChars", 50);
    }

    private UserPreference pref(String key, String value) {
        return new UserPreference(null, userId, key, value, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void resolveForUser_noOverride_returnsAllSystemDefaults() {
        when(repo.findByUserId(userId)).thenReturn(List.of());

        AutoSnapshotConfig cfg = resolver.resolveForUser(userId);

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.retain()).isEqualTo(50);
        assertThat(cfg.intervalSeconds()).isEqualTo(60);
        assertThat(cfg.diffChars()).isEqualTo(50);
    }

    @Test
    void resolveForUser_fullOverride_returnsAllUserValues() {
        when(repo.findByUserId(userId)).thenReturn(List.of(
            pref("version.auto.enabled", "false"),
            pref("version.auto.retain", "30"),
            pref("version.auto.interval-seconds", "120"),
            pref("version.auto.diff-chars", "100")
        ));

        AutoSnapshotConfig cfg = resolver.resolveForUser(userId);

        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.retain()).isEqualTo(30);
        assertThat(cfg.intervalSeconds()).isEqualTo(120);
        assertThat(cfg.diffChars()).isEqualTo(100);
    }

    @Test
    void resolveForUser_partialOverride_mixedSources() {
        when(repo.findByUserId(userId)).thenReturn(List.of(
            pref("version.auto.retain", "100"),
            pref("version.auto.diff-chars", "0")
        ));

        AutoSnapshotConfig cfg = resolver.resolveForUser(userId);

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.retain()).isEqualTo(100);
        assertThat(cfg.intervalSeconds()).isEqualTo(60);
        assertThat(cfg.diffChars()).isEqualTo(0);
    }

    @Test
    void resolveForUser_disabledFlag_disablesAutoSnapshot() {
        when(repo.findByUserId(userId)).thenReturn(List.of(
            pref("version.auto.enabled", "false")
        ));

        AutoSnapshotConfig cfg = resolver.resolveForUser(userId);

        assertThat(cfg.enabled()).isFalse();
    }
}
