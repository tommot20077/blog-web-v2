package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.version.consumer.ArticleVersionConsumer;
import dowob.xyz.blog.module.version.mapper.VersionMapper;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.LatestSnapshotRef;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自動快照一次事件處理的查詢次數守衛（PERF-21）。
 *
 * <p>以真實的 consumer、policy、service 串起來（只 mock 外部邊界），驗證 SAVED 事件經過「判斷＋寫入」兩段後：
 * 文章內容只查 1 次、使用者設定只解析 1 次、上次快照只走輕量投影（不整列查詢）。
 * 舊流程 判斷與寫入 各自查一次文章與設定，共 2 次。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("自動快照 一次事件只查一次（PERF-21）")
class AutoSnapshotSingleLookupTest {

    @Mock private ArticleFacade articleFacade;
    @Mock private ArticleVersionRepository versionRepo;
    @Mock private VersionMapper versionMapper;
    @Mock private PreferenceResolver preferenceResolver;
    @Mock private TagFacade tagFacade;
    @Mock private IdempotencyService idempotencyService;

    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final UUID articleUuid = UUID.randomUUID();
    private final UUID authorUuid = UUID.randomUUID();

    private ArticleVersionConsumer consumer;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(idempotencyService.tryMark(any(), anyString())).thenReturn(true);
        when(tagFacade.findTagIdsByArticleUuid(any())).thenReturn(List.of());
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TransactionTemplate tx = new TransactionTemplate(txManager);
        AutoSnapshotPolicy policy = new AutoSnapshotPolicy(articleFacade, versionMapper, preferenceResolver);
        VersioningService service = new VersioningService(
                articleFacade, versionRepo, versionMapper, preferenceResolver, tagFacade, tx);
        consumer = new ArticleVersionConsumer(service, policy, new ConsumerExecutor(tx, idempotencyService));
    }

    private void givenArticleAndConfig() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of(new ArticleContentData(
                articleUuid, "標題", "slug", "a".repeat(200), "摘要", null, "DRAFT", authorUuid)));
        when(preferenceResolver.resolveForUser(authorUuid)).thenReturn(new AutoSnapshotConfig(true, 7, 60, 50));
    }

    private ArticleContentChangedEvent saved() {
        return new ArticleContentChangedEvent(UUID.randomUUID(), articleUuid, null, Action.SAVED, Instant.now());
    }

    @Test
    @DisplayName("SAVED 且該寫快照：文章內容 1 次、設定 1 次；快照以判斷當下的內容寫入；retain 用同一份設定")
    void saved_shouldSnapshot_queriesArticleAndConfigOnce() {
        givenArticleAndConfig();
        when(versionMapper.findLatestRef(articleUuid, "AUTO")).thenReturn(Optional.empty());

        consumer.onContentChanged(saved());

        verify(articleFacade, times(1)).findContentByUuid(articleUuid);
        verify(preferenceResolver, times(1)).resolveForUser(authorUuid);
        verify(versionMapper, times(1)).findLatestRef(articleUuid, "AUTO");
        org.mockito.ArgumentCaptor<ArticleVersion> saved = org.mockito.ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo("a".repeat(200));
        assertThat(saved.getValue().getType()).isEqualTo("AUTO");
        verify(versionMapper).retainAuto(articleUuid, 7);
    }

    @Test
    @DisplayName("SAVED 但未達門檻：文章內容 1 次、設定 1 次，不寫快照")
    void saved_belowThreshold_queriesOnce_noWrite() {
        givenArticleAndConfig();
        when(versionMapper.findLatestRef(articleUuid, "AUTO"))
                .thenReturn(Optional.of(new LatestSnapshotRef(9L, LocalDateTime.now().minusSeconds(5))));

        consumer.onContentChanged(saved());

        verify(articleFacade, times(1)).findContentByUuid(articleUuid);
        verify(preferenceResolver, times(1)).resolveForUser(authorUuid);
        verify(versionMapper, never()).findContentLength(any());
        verify(versionRepo, never()).save(any());
        verify(versionMapper, never()).retainAuto(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
