package dowob.xyz.blog.module.file.job;

import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import dowob.xyz.blog.module.file.service.FileArticleCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FileArticleOrphanReconciliationJob} 單元測試：子類別提供的各個鉤子與排程標註
 * （框架行為由 blog-infrastructure 的測試涵蓋，整體流程見 {@code FileArticleOrphanReconciliationJobIT}）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("FileArticleOrphanReconciliationJob 單元測試")
class FileArticleOrphanReconciliationJobTest {

    private FileMetadataRepository repository;
    private ArticleLookupFacade articleLookupFacade;
    private FileArticleCleanupService cleanupService;
    private FileArticleOrphanReconciliationJob job;

    @BeforeEach
    void setUp() {
        repository = mock(FileMetadataRepository.class);
        articleLookupFacade = mock(ArticleLookupFacade.class);
        cleanupService = mock(FileArticleCleanupService.class);
        job = new FileArticleOrphanReconciliationJob(mock(StringRedisTemplate.class), repository, articleLookupFacade, cleanupService);
    }

    @Test
    @DisplayName("module 與 table 識別為 file／file_metadata")
    void identity_isFileAndFileMetadata() {
        assertThat(job.module()).isEqualTo("file");
        assertThat(job.table()).isEqualTo("file_metadata");
    }

    @Test
    @DisplayName("fetchReferencedUuids：原樣傳遞游標與筆數給 repository")
    void fetchReferencedUuids_delegatesWithCursor() {
        UUID after = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        when(repository.findDistinctArticleUuidsAfter(after, 500)).thenReturn(List.of(a));

        assertThat(job.fetchReferencedUuids(after, 500)).containsExactly(a);
    }

    @Test
    @DisplayName("filterExisting：委派 ArticleLookupFacade.filterExistingUuids（不分狀態：草稿文章的檔案不是孤兒）")
    void filterExisting_delegatesToLookupFacade() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(articleLookupFacade.filterExistingUuids(Set.of(a, b))).thenReturn(Set.of(a));

        assertThat(job.filterExisting(Set.of(a, b))).containsExactly(a);
    }

    @Test
    @DisplayName("removeOrphans：對每個已不存在的文章套用與事件路徑相同的清理，回傳刪除檔案總數")
    void removeOrphans_appliesSameCleanupAsEventPath() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(cleanupService.deleteFilesBoundToDeletedArticle(a)).thenReturn(2);
        when(cleanupService.deleteFilesBoundToDeletedArticle(b)).thenReturn(1);

        assertThat(job.removeOrphans(List.of(a, b))).isEqualTo(3);
    }

    @Test
    @DisplayName("removeOrphans：空集合不呼叫清理服務，回傳 0")
    void removeOrphans_empty_returnsZero() {
        assertThat(job.removeOrphans(List.of())).isZero();
        verifyNoInteractions(cleanupService);
    }

    @Test
    @DisplayName("removeOrphans：清理失敗（例如 MinIO 掛了）時例外往外拋，由框架標記 failed、游標不推進")
    void removeOrphans_cleanupFails_propagates() {
        UUID a = UUID.randomUUID();
        when(cleanupService.deleteFilesBoundToDeletedArticle(a)).thenThrow(new IllegalStateException("MinIO down"));

        assertThatThrownBy(() -> job.removeOrphans(List.of(a))).isInstanceOf(IllegalStateException.class);
        verify(cleanupService).deleteFilesBoundToDeletedArticle(a);
    }

    @Test
    @DisplayName("排程：恰有一個 @Scheduled 方法，cron 為每日一次的固定時刻（04:23，與其他校正 job 的分鐘數錯開）")
    void scheduledMethod_usesDailyFixedCron() {
        List<Method> scheduled = Arrays.stream(FileArticleOrphanReconciliationJob.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .toList();

        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.get(0).getAnnotation(Scheduled.class).cron())
                .isEqualTo(FileArticleOrphanReconciliationJob.CRON)
                .isEqualTo("0 23 4 * * *");
    }
}
