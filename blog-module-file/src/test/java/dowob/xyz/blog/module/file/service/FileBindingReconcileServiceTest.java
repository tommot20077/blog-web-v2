package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.exception.SystemException;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FileBindingReconcileService} 單元測試（B1 補綁定、D6 未綁定檔清理）。
 *
 * <p>SQL 層的條件（未綁定、非頭像、建立時間）由 {@code FileBindingReconcileJobIT} 以真實 DB 驗證；
 * 這裡驗證服務層的流程、fail-safe，以及「即使 repository 回傳了不該刪的列也不刪」的縱深防護。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("FileBindingReconcileService 單元測試")
class FileBindingReconcileServiceTest {

    private static final UUID NIL = new UUID(0L, 0L);
    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 11, 4, 4, 17);
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 10, 31, 4, 17);

    private ArticleLookupFacade facade;
    private ArticleFileBindingService bindingService;
    private FileMetadataRepository repository;
    private FileService fileService;
    private FileBindingReconcileService service;

    @BeforeEach
    void setUp() {
        facade = mock(ArticleLookupFacade.class);
        bindingService = mock(ArticleFileBindingService.class);
        repository = mock(FileMetadataRepository.class);
        fileService = mock(FileService.class);
        when(fileService.deleteUnboundFileIfUnreferenced(any(), any())).thenReturn(true);
        service = new FileBindingReconcileService(facade, bindingService, repository, fileService, 2);
    }

    private static FileMetadata file(UUID uploader, UsageType type, UUID articleUuid, LocalDateTime createdAt) {
        FileMetadata f = new FileMetadata();
        f.setId(UUID.randomUUID());
        f.setUploaderId(uploader);
        f.setUsageType(type);
        f.setArticleUuid(articleUuid);
        f.setCreatedAt(createdAt);
        f.setStoragePath("articles/2026/10/01/" + f.getId() + ".jpg");
        return f;
    }

    private static FileMetadata candidate(UUID uploader) {
        return file(uploader, UsageType.ARTICLE_CONTENT, null, CUTOFF.minusDays(1));
    }

    /** B1：補綁定 */
    @Nested
    @DisplayName("rebindRecentlyUpdatedArticles（B1）")
    class Rebind {

        @Test
        @DisplayName("以 keyset 游標分頁取得近期更新文章，對每一篇都呼叫綁定邏輯")
        void rebind_pagesThroughKeysetAndRebindsEveryArticle() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            when(facade.findUuidsUpdatedSinceAnyStatus(SINCE, null, 2)).thenReturn(List.of(a, b));
            when(facade.findUuidsUpdatedSinceAnyStatus(SINCE, b, 2)).thenReturn(List.of(c));
            when(bindingService.rebindFromArticleContent(any())).thenReturn(true);

            var result = service.rebindRecentlyUpdatedArticles(SINCE, 100, () -> false);

            verify(bindingService).rebindFromArticleContent(a);
            verify(bindingService).rebindFromArticleContent(b);
            verify(bindingService).rebindFromArticleContent(c);
            assertThat(result.scanned()).isEqualTo(3);
            assertThat(result.rebound()).isEqualTo(3);
            assertThat(result.failed()).isZero();
            assertThat(result.truncated()).isFalse();
        }

        @Test
        @DisplayName("文章已不存在（綁定邏輯回 false）→ 算掃描、不算 rebound")
        void rebind_articleGone_countedAsScannedNotRebound() {
            UUID a = UUID.randomUUID();
            when(facade.findUuidsUpdatedSinceAnyStatus(SINCE, null, 2)).thenReturn(List.of(a));
            when(bindingService.rebindFromArticleContent(a)).thenReturn(false);

            var result = service.rebindRecentlyUpdatedArticles(SINCE, 100, () -> false);

            assertThat(result.scanned()).isEqualTo(1);
            assertThat(result.rebound()).isZero();
        }

        @Test
        @DisplayName("fail-safe：facade 列舉文章失敗 → 例外往外拋（由 job 整次中止）")
        void rebind_facadeListingThrows_propagates() {
            when(facade.findUuidsUpdatedSinceAnyStatus(any(), any(), anyInt())).thenThrow(new IllegalStateException("article db down"));

            assertThatThrownBy(() -> service.rebindRecentlyUpdatedArticles(SINCE, 100, () -> false))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("單篇回填失敗 → 記為 failed，繼續處理其餘文章，不外拋")
        void rebind_oneArticleFails_continuesWithRemaining() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(facade.findUuidsUpdatedSinceAnyStatus(SINCE, null, 2)).thenReturn(List.of(a, b));
            when(facade.findUuidsUpdatedSinceAnyStatus(SINCE, b, 2)).thenReturn(List.of());
            when(bindingService.rebindFromArticleContent(a)).thenThrow(new RuntimeException("boom"));
            when(bindingService.rebindFromArticleContent(b)).thenReturn(true);

            var result = service.rebindRecentlyUpdatedArticles(SINCE, 100, () -> false);

            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.rebound()).isEqualTo(1);
            verify(bindingService).rebindFromArticleContent(b);
        }

        @Test
        @DisplayName("單次處理上限：達到 maxArticles 即停止，truncated=true")
        void rebind_reachesMaxArticles_truncated() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(facade.findUuidsUpdatedSinceAnyStatus(SINCE, null, 2)).thenReturn(List.of(a, b));
            when(bindingService.rebindFromArticleContent(any())).thenReturn(true);

            var result = service.rebindRecentlyUpdatedArticles(SINCE, 2, () -> false);

            assertThat(result.scanned()).isEqualTo(2);
            assertThat(result.truncated()).isTrue();
            verify(facade, never()).findUuidsUpdatedSinceAnyStatus(SINCE, b, 2);
        }

        @Test
        @DisplayName("時間預算用完 → 不再查詢與回填，truncated=true")
        void rebind_timeUp_stopsImmediately() {
            var result = service.rebindRecentlyUpdatedArticles(SINCE, 100, () -> true);

            assertThat(result.truncated()).isTrue();
            assertThat(result.scanned()).isZero();
            verifyNoInteractions(facade, bindingService);
        }
    }

    /** D6：清理 */
    @Nested
    @DisplayName("cleanupUnboundFiles（D6）")
    class Cleanup {

        @Test
        @DisplayName("只刪「未被作者任何文章引用」的候選；被引用的保留；刪除走交易內鎖列重驗的專用方法")
        void cleanup_deletesOnlyUnreferencedCandidates() {
            UUID author = UUID.randomUUID();
            FileMetadata orphan = candidate(author);
            FileMetadata referenced = candidate(author);
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, NIL, 2)).thenReturn(List.of(orphan, referenced));
            when(facade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of(referenced.getId()));

            var result = service.cleanupUnboundFiles(CUTOFF, null, 100, 1000, () -> false);

            verify(fileService).deleteUnboundFileIfUnreferenced(orphan.getId(), CUTOFF);
            verify(fileService, never()).deleteUnboundFileIfUnreferenced(eq(referenced.getId()), any());
            assertThat(result.deleted()).isEqualTo(1);
            assertThat(result.keptReferenced()).isEqualTo(1);
        }

        @Test
        @DisplayName("縱深防護：頭像、已綁定、未滿保留天數的列即使被 repository 回傳也不刪、也不用來查引用")
        void cleanup_neverDeletesAvatarBoundOrTooNew_evenIfRepositoryReturnsThem() {
            UUID author = UUID.randomUUID();
            FileMetadata avatar = file(author, UsageType.AVATAR, null, CUTOFF.minusDays(30));
            FileMetadata bound = file(author, UsageType.ARTICLE_CONTENT, UUID.randomUUID(), CUTOFF.minusDays(30));
            FileMetadata tooNew = file(author, UsageType.ARTICLE_CONTENT, null, CUTOFF.plusDays(1));
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, NIL, 2)).thenReturn(List.of(avatar, bound));
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, bound.getId(), 2)).thenReturn(List.of(tooNew));
            when(facade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of());

            var result = service.cleanupUnboundFiles(CUTOFF, null, 100, 1000, () -> false);

            verify(fileService, never()).deleteUnboundFileIfUnreferenced(any(), any());
            assertThat(result.deleted()).isZero();
        }

        @Test
        @DisplayName("fail-safe：查詢作者文章引用的 facade 拋例外 → 整次中止，零刪除")
        void cleanup_facadeFails_deletesNothing() {
            UUID authorA = UUID.randomUUID();
            UUID authorB = UUID.randomUUID();
            FileMetadata a = candidate(authorA);
            FileMetadata b = candidate(authorB);
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, NIL, 2)).thenReturn(List.of(a, b));
            when(facade.findFileUuidsReferencedByAuthorArticles(authorA)).thenReturn(Set.of());
            when(facade.findFileUuidsReferencedByAuthorArticles(authorB)).thenThrow(new IllegalStateException("article db down"));

            assertThatThrownBy(() -> service.cleanupUnboundFiles(CUTOFF, null, 100, 1000, () -> false))
                    .isInstanceOf(IllegalStateException.class);

            verify(fileService, never()).deleteUnboundFileIfUnreferenced(any(), any());
        }

        @Test
        @DisplayName("引用集合依上傳者各查一次（同一上傳者的多個檔案共用）")
        void cleanup_queriesReferencedSetOncePerUploader() {
            UUID author = UUID.randomUUID();
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, NIL, 2))
                    .thenReturn(List.of(candidate(author), candidate(author)));
            when(facade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of());

            service.cleanupUnboundFiles(CUTOFF, null, 100, 1000, () -> false);

            verify(facade).findFileUuidsReferencedByAuthorArticles(author);
        }

        @Test
        @DisplayName("嚴格刪除語意：某檔 MinIO 刪除失敗 → 例外往外拋（metadata 由 deleteFile 保留，留到下次）；其餘檔案仍嘗試")
        void cleanup_deleteFailure_propagatesAndOthersStillAttempted() {
            UUID author = UUID.randomUUID();
            FileMetadata failing = candidate(author);
            FileMetadata fine = candidate(author);
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, NIL, 2)).thenReturn(List.of(failing, fine));
            when(facade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of());
            doThrow(new SystemException(CommonErrorCode.STORAGE_ERROR)).when(fileService)
                    .deleteUnboundFileIfUnreferenced(failing.getId(), CUTOFF);

            assertThatThrownBy(() -> service.cleanupUnboundFiles(CUTOFF, null, 100, 1000, () -> false))
                    .isInstanceOf(SystemException.class);

            verify(fileService).deleteUnboundFileIfUnreferenced(fine.getId(), CUTOFF);
        }

        @Test
        @DisplayName("刪除當下重驗不通過（掃描後被綁定或被引用，專用方法回 false）→ 不算刪除")
        void cleanup_recheckRejectsAtDeleteTime_notCountedAsDeleted() {
            UUID author = UUID.randomUUID();
            FileMetadata f = candidate(author);
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, NIL, 2)).thenReturn(List.of(f));
            when(facade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of());
            when(fileService.deleteUnboundFileIfUnreferenced(f.getId(), CUTOFF)).thenReturn(false);

            var result = service.cleanupUnboundFiles(CUTOFF, null, 100, 1000, () -> false);

            assertThat(result.deleted()).isZero();
        }

        @Test
        @DisplayName("單次刪除上限：達上限即停止，回傳停在上限前最後一筆的游標，truncated=true")
        void cleanup_reachesMaxDeletes_stopsAndReturnsCursor() {
            UUID author = UUID.randomUUID();
            FileMetadata f1 = candidate(author);
            FileMetadata f2 = candidate(author);
            FileMetadata f3 = candidate(author);
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, NIL, 2)).thenReturn(List.of(f1, f2));
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, f2.getId(), 2)).thenReturn(List.of(f3));
            when(facade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of());

            var result = service.cleanupUnboundFiles(CUTOFF, null, 2, 1000, () -> false);

            verify(fileService).deleteUnboundFileIfUnreferenced(f1.getId(), CUTOFF);
            verify(fileService).deleteUnboundFileIfUnreferenced(f2.getId(), CUTOFF);
            verify(fileService, never()).deleteUnboundFileIfUnreferenced(eq(f3.getId()), any());
            assertThat(result.deleted()).isEqualTo(2);
            assertThat(result.truncated()).isTrue();
            assertThat(result.nextCursor()).isEqualTo(f2.getId());
        }

        @Test
        @DisplayName("掃完整輪（最後一頁不滿頁）→ nextCursor 為 null、truncated=false")
        void cleanup_scanFinished_cursorNull() {
            UUID author = UUID.randomUUID();
            FileMetadata f1 = candidate(author);
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, NIL, 2)).thenReturn(List.of(f1));
            when(facade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of(f1.getId()));

            var result = service.cleanupUnboundFiles(CUTOFF, null, 100, 1000, () -> false);

            assertThat(result.nextCursor()).isNull();
            assertThat(result.truncated()).isFalse();
            assertThat(result.scanned()).isEqualTo(1);
        }

        @Test
        @DisplayName("單次掃描上限：達 maxScan 即停止並回傳游標（被引用而保留的檔案不會讓後面的檔案餓死）")
        void cleanup_reachesMaxScan_returnsCursorToContinueNextRun() {
            UUID author = UUID.randomUUID();
            FileMetadata f1 = candidate(author);
            FileMetadata f2 = candidate(author);
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, NIL, 2)).thenReturn(List.of(f1, f2));
            when(facade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of(f1.getId(), f2.getId()));

            var result = service.cleanupUnboundFiles(CUTOFF, null, 100, 2, () -> false);

            assertThat(result.nextCursor()).isEqualTo(f2.getId());
            assertThat(result.truncated()).isTrue();
            verify(repository, never()).findUnboundNonAvatarCreatedBefore(CUTOFF, f2.getId(), 2);
        }

        @Test
        @DisplayName("從上次游標續跑：游標原樣傳給 repository")
        void cleanup_resumesFromGivenCursor() {
            UUID cursor = UUID.randomUUID();
            when(repository.findUnboundNonAvatarCreatedBefore(CUTOFF, cursor, 2)).thenReturn(List.of());

            var result = service.cleanupUnboundFiles(CUTOFF, cursor, 100, 1000, () -> false);

            verify(repository).findUnboundNonAvatarCreatedBefore(CUTOFF, cursor, 2);
            assertThat(result.nextCursor()).isNull();
        }

        @Test
        @DisplayName("時間預算用完 → 不查、不刪，游標維持原值，truncated=true")
        void cleanup_timeUp_doesNothingAndKeepsCursor() {
            UUID cursor = UUID.randomUUID();

            var result = service.cleanupUnboundFiles(CUTOFF, cursor, 100, 1000, () -> true);

            assertThat(result.nextCursor()).isEqualTo(cursor);
            assertThat(result.truncated()).isTrue();
            verifyNoInteractions(repository, facade, fileService);
        }
    }
}
