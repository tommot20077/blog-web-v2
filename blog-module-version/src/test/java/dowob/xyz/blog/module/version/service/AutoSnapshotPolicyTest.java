package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.module.version.mapper.VersionMapper;
import dowob.xyz.blog.module.version.model.LatestSnapshotRef;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AutoSnapshotPolicy} 單元測試。
 *
 * <p>PERF-21：判斷「距上次快照多久、內容差多少字」只需要上次快照的建立時間與內容長度，
 * 因此改走 {@link VersionMapper#findLatestRef}／{@link VersionMapper#findContentLength} 的輕量投影，不再把上次快照的整份 Markdown 撈回應用程式。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AutoSnapshotPolicy 單元測試")
class AutoSnapshotPolicyTest {

    @Mock private ArticleFacade articleFacade;
    @Mock private VersionMapper versionMapper;
    @Mock private PreferenceResolver preferenceResolver;
    @InjectMocks private AutoSnapshotPolicy policy;

    private final UUID articleUuid = UUID.randomUUID();
    private final UUID authorUuid = UUID.randomUUID();

    private ArticleContentData buildContentData(UUID articleUuid, UUID authorUuid, String content) {
        return new ArticleContentData(articleUuid,
            "T", "s", content, "sum", null, "PUBLISHED", authorUuid
        );
    }

    private static final long LAST_ID = 77L;

    /** 上次 AUTO 快照：參照（主鍵＋時間）與其內容長度各由一個查詢提供。 */
    private void givenLast(int contentLength, LocalDateTime createdAt) {
        when(versionMapper.findLatestRef(articleUuid, "AUTO"))
            .thenReturn(Optional.of(new LatestSnapshotRef(LAST_ID, createdAt)));
        lenient().when(versionMapper.findContentLength(LAST_ID)).thenReturn(Optional.of(contentLength));
    }

    private void givenArticle(String content, AutoSnapshotConfig cfg) {
        when(articleFacade.findContentByUuid(articleUuid))
            .thenReturn(Optional.of(buildContentData(articleUuid, authorUuid, content)));
        when(preferenceResolver.resolveForUser(authorUuid)).thenReturn(cfg);
    }

    @Test
    @DisplayName("功能關閉 → false，且不查上次快照")
    void shouldSnapshot_disabled_returnsFalse() {
        givenArticle("aaa", new AutoSnapshotConfig(false, 50, 60, 50));

        assertThat(policy.plan(articleUuid).isPresent()).isFalse();
        verify(versionMapper, never()).findLatestRef(any(), any());
    }

    @Test
    @DisplayName("沒有任何 AUTO 快照 → true")
    void shouldSnapshot_firstTime_returnsTrue() {
        givenArticle("aaa", new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionMapper.findLatestRef(articleUuid, "AUTO")).thenReturn(Optional.empty());

        assertThat(policy.plan(articleUuid).isPresent()).isTrue();
    }

    @Test
    @DisplayName("距上次未達時間間隔 → false")
    void shouldSnapshot_intervalNotElapsed_returnsFalse() {
        givenArticle("a".repeat(200), new AutoSnapshotConfig(true, 50, 60, 50));
        givenLast(100, LocalDateTime.now().minusSeconds(30));

        assertThat(policy.plan(articleUuid).isPresent()).isFalse();
    }

    @Test
    @DisplayName("字元差距不足 → false")
    void shouldSnapshot_diffNotEnough_returnsFalse() {
        givenArticle("a".repeat(110), new AutoSnapshotConfig(true, 50, 60, 50));
        givenLast(100, LocalDateTime.now().minusSeconds(120));

        assertThat(policy.plan(articleUuid).isPresent()).isFalse();
    }

    @Test
    @DisplayName("diffChars=0 → 略過字元差距檢查，時間間隔已過即 true")
    void shouldSnapshot_diffCharsZero_skipsDiffCheck_returnsTrueWhenIntervalPassed() {
        givenArticle("a".repeat(100), new AutoSnapshotConfig(true, 50, 60, 0));
        givenLast(100, LocalDateTime.now().minusSeconds(120));

        assertThat(policy.plan(articleUuid).isPresent()).isTrue();
    }

    @Test
    @DisplayName("時間間隔與字元差距都達標 → true")
    void shouldSnapshot_intervalAndDiffPass_returnsTrue() {
        givenArticle("a".repeat(200), new AutoSnapshotConfig(true, 50, 60, 50));
        givenLast(100, LocalDateTime.now().minusSeconds(120));

        assertThat(policy.plan(articleUuid).isPresent()).isTrue();
    }

    @Test
    @DisplayName("文章不存在 → false")
    void shouldSnapshot_articleNotFound_returnsFalse() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.empty());

        assertThat(policy.plan(articleUuid).isPresent()).isFalse();
    }

    @Test
    @DisplayName("邊界：字元差距剛好等於門檻 → true；少 1 → false（絕對值，內容變短也算）")
    void shouldSnapshot_diffBoundary() {
        LocalDateTime longAgo = LocalDateTime.now().minusSeconds(600);
        AutoSnapshotConfig cfg = new AutoSnapshotConfig(true, 50, 60, 50);

        givenArticle("a".repeat(150), cfg);
        givenLast(100, longAgo);
        assertThat(policy.plan(articleUuid).isPresent()).as("差 50 = 門檻").isTrue();

        givenArticle("a".repeat(149), cfg);
        assertThat(policy.plan(articleUuid).isPresent()).as("差 49 < 門檻").isFalse();

        givenArticle("a".repeat(50), cfg);
        assertThat(policy.plan(articleUuid).isPresent()).as("變短 50 也算差距").isTrue();
    }

    @Test
    @DisplayName("邊界：距上次剛好滿時間間隔 → 通過時間門檻；差 1 秒 → 不通過")
    void shouldSnapshot_intervalBoundary() {
        AutoSnapshotConfig cfg = new AutoSnapshotConfig(true, 50, 60, 0);
        givenArticle("x", cfg);

        givenLast(1, LocalDateTime.now().minusSeconds(60));
        assertThat(policy.plan(articleUuid).isPresent()).as("剛好 60 秒").isTrue();

        givenLast(1, LocalDateTime.now().minusSeconds(58));
        assertThat(policy.plan(articleUuid).isPresent()).as("只過 58 秒").isFalse();
    }

    @Test
    @DisplayName("補充平面字元（emoji）以 UTF-16 code unit 計：與舊行為（Java String.length()）一致")
    void shouldSnapshot_supplementaryCharacters_countedAsUtf16Units() {
        String emojis = "😀".repeat(30);
        assertThat(emojis.length()).isEqualTo(60);
        givenArticle(emojis, new AutoSnapshotConfig(true, 50, 60, 50));
        givenLast(10, LocalDateTime.now().minusSeconds(120));

        assertThat(policy.plan(articleUuid).isPresent()).as("60 - 10 = 50 = 門檻").isTrue();
    }

    // ─── plan：把已查好的文章與設定交給後續寫入，不重查 ───

    @Test
    @DisplayName("plan：該寫快照時回傳判斷當下查到的文章內容與設定（供 recordAutoSnapshot 直接使用）")
    void plan_whenShouldSnapshot_returnsArticleAndConfig() {
        AutoSnapshotConfig cfg = new AutoSnapshotConfig(true, 50, 60, 50);
        ArticleContentData article = buildContentData(articleUuid, authorUuid, "a".repeat(200));
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of(article));
        when(preferenceResolver.resolveForUser(authorUuid)).thenReturn(cfg);
        when(versionMapper.findLatestRef(articleUuid, "AUTO")).thenReturn(Optional.empty());

        Optional<AutoSnapshotPlan> plan = policy.plan(articleUuid);

        assertThat(plan).isPresent();
        assertThat(plan.get().article()).isSameAs(article);
        assertThat(plan.get().config()).isSameAs(cfg);
    }

    @Test
    @DisplayName("plan：不該寫快照（功能關閉／未達門檻／文章不存在）→ empty")
    void plan_whenShouldNotSnapshot_isEmpty() {
        givenArticle("aaa", new AutoSnapshotConfig(false, 50, 60, 50));

        assertThat(policy.plan(articleUuid)).isEmpty();
    }

    @Test
    @DisplayName("一次判斷只查一次文章內容、一次設定、一次上次快照參照與一次長度（上次快照只走輕量投影）")
    void plan_queriesEachSourceExactlyOnce() {
        givenArticle("a".repeat(200), new AutoSnapshotConfig(true, 50, 60, 50));
        givenLast(100, LocalDateTime.now().minusSeconds(120));

        policy.plan(articleUuid);

        verify(articleFacade, times(1)).findContentByUuid(articleUuid);
        verify(preferenceResolver, times(1)).resolveForUser(authorUuid);
        verify(versionMapper, times(1)).findLatestRef(articleUuid, "AUTO");
        verify(versionMapper, times(1)).findContentLength(LAST_ID);
    }

    // ─── PERF-21：時間間隔不足時不計算長度 ───

    @Test
    @DisplayName("間隔不足 → 不呼叫取長度的投影（連續存檔的常見情況不做全文掃描）")
    void plan_intervalNotElapsed_neverComputesContentLength() {
        givenArticle("a".repeat(500), new AutoSnapshotConfig(true, 50, 60, 50));
        givenLast(1, LocalDateTime.now().minusSeconds(5));

        assertThat(policy.plan(articleUuid)).isEmpty();

        verify(versionMapper, never()).findContentLength(any());
    }

    @Test
    @DisplayName("diffChars=0（純時間觸發）→ 間隔通過也不需要長度，不呼叫取長度的投影")
    void plan_diffCharsZero_neverComputesContentLength() {
        givenArticle("a".repeat(500), new AutoSnapshotConfig(true, 50, 60, 0));
        givenLast(1, LocalDateTime.now().minusSeconds(600));

        assertThat(policy.plan(articleUuid)).isPresent();

        verify(versionMapper, never()).findContentLength(any());
    }

    @Test
    @DisplayName("沒有任何 AUTO 快照 → 直接寫，不取長度")
    void plan_noPreviousSnapshot_neverComputesContentLength() {
        givenArticle("x", new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionMapper.findLatestRef(articleUuid, "AUTO")).thenReturn(Optional.empty());

        assertThat(policy.plan(articleUuid)).isPresent();

        verify(versionMapper, never()).findContentLength(any());
    }

    @Test
    @DisplayName("取長度時該列已被滾動保留刪掉（empty）→ 視同沒有可比較的前一份，寫快照")
    void plan_previousRowVanished_snapshots() {
        givenArticle("x", new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionMapper.findLatestRef(articleUuid, "AUTO"))
            .thenReturn(Optional.of(new LatestSnapshotRef(LAST_ID, LocalDateTime.now().minusSeconds(600))));
        when(versionMapper.findContentLength(LAST_ID)).thenReturn(Optional.empty());

        assertThat(policy.plan(articleUuid)).isPresent();
    }
}
