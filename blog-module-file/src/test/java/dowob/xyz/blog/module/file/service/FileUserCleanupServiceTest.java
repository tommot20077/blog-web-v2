package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.api.errorcode.FileErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileUserCleanupService} 單元測試（DATA-02／DATA-07）。
 *
 * <p>判斷「檔案是否仍被保留文章引用」只用既有的跨模組資訊：檔案的 {@code article_uuid} 綁定，
 * 加上 {@link ArticleLookupFacade#findByUuid} 取得的文章狀態；去留政策委派 {@code ArticleStatus}，不自行硬寫狀態集合。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileUserCleanupService")
class FileUserCleanupServiceTest {

    @Mock private FileMetadataRepository fileMetadataRepository;
    @Mock private FileService fileService;
    @Mock private ArticleLookupFacade articleLookupFacade;

    private FileUserCleanupService service;
    private final UUID uploader = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new FileUserCleanupService(fileMetadataRepository, fileService, articleLookupFacade);
    }

    private FileMetadata file(UsageType usageType, UUID articleUuid) {
        FileMetadata metadata = new FileMetadata();
        metadata.setId(UUID.randomUUID());
        metadata.setUploaderId(uploader);
        metadata.setUsageType(usageType);
        metadata.setArticleUuid(articleUuid);
        return metadata;
    }

    private void stubFiles(FileMetadata... files) {
        when(fileMetadataRepository.findByUploaderIdOrderByCreatedAtDesc(uploader)).thenReturn(List.of(files));
    }

    private void stubArticle(UUID articleUuid, String status) {
        when(articleLookupFacade.findByUuid(articleUuid))
                .thenReturn(Optional.of(new ArticleData(articleUuid, status, null, uploader, null)));
    }

    @Test
    @DisplayName("未綁定任何文章的檔案：刪除（以上傳者本人身分走既有 deleteFile，含 MinIO 物件與交易回滾保護）")
    void unboundFile_isDeletedThroughFileService() {
        FileMetadata unbound = file(UsageType.ARTICLE_CONTENT, null);
        stubFiles(unbound);

        int deleted = service.deleteFilesNotReferencedByRetainedArticles(uploader);

        assertThat(deleted).isEqualTo(1);
        verify(fileService).deleteFile(unbound.getId(), uploader, false);
    }

    @Test
    @DisplayName("M4：封面檔 article_uuid 為 NULL，但被已發布文章的 coverImageUrl 引用 → 保留")
    void unboundCoverFile_referencedByRetainedArticle_isKept() {
        FileMetadata cover = file(UsageType.ARTICLE_COVER, null);
        FileMetadata unreferenced = file(UsageType.ARTICLE_CONTENT, null);
        stubFiles(cover, unreferenced);
        when(articleLookupFacade.findFileUuidsReferencedByRetainedArticles(uploader)).thenReturn(Set.of(cover.getId()));

        int deleted = service.deleteFilesNotReferencedByRetainedArticles(uploader);

        assertThat(deleted).isEqualTo(1);
        verify(fileService, never()).deleteFile(cover.getId(), uploader, false);
        verify(fileService).deleteFile(unreferenced.getId(), uploader, false);
    }

    @Test
    @DisplayName("M4：檔案綁在草稿，但同時被已發布文章內文引用 → 保留（不論 article_uuid 綁到哪）")
    void fileBoundToDraft_butReferencedByRetainedArticle_isKept() {
        UUID draft = UUID.randomUUID();
        FileMetadata shared = file(UsageType.ARTICLE_CONTENT, draft);
        stubFiles(shared);
        stubArticle(draft, "DRAFT");
        when(articleLookupFacade.findFileUuidsReferencedByRetainedArticles(uploader)).thenReturn(Set.of(shared.getId()));

        assertThat(service.deleteFilesNotReferencedByRetainedArticles(uploader)).isZero();
        verify(fileService, never()).deleteFile(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("M4：查詢保留文章引用失敗 → 整體往外拋、不刪任何檔案")
    void retainedReferenceLookupFails_deletesNothingAndThrows() {
        stubFiles(file(UsageType.ARTICLE_CONTENT, null));
        when(articleLookupFacade.findFileUuidsReferencedByRetainedArticles(uploader))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.deleteFilesNotReferencedByRetainedArticles(uploader))
                .isInstanceOf(IllegalStateException.class);
        verify(fileService, never()).deleteFile(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("綁定已發布文章的檔案：保留（文章保留，圖片不能破）")
    void fileBoundToPublishedArticle_isKept() {
        UUID article = UUID.randomUUID();
        FileMetadata bound = file(UsageType.ARTICLE_CONTENT, article);
        stubFiles(bound);
        stubArticle(article, "PUBLISHED");

        int deleted = service.deleteFilesNotReferencedByRetainedArticles(uploader);

        assertThat(deleted).isZero();
        verify(fileService, never()).deleteFile(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("綁定已下架文章的檔案：保留（已下架文章依 ArticleStatus 政策保留）")
    void fileBoundToArchivedArticle_isKept() {
        UUID article = UUID.randomUUID();
        stubFiles(file(UsageType.ARTICLE_COVER, article));
        stubArticle(article, "ARCHIVED");

        assertThat(service.deleteFilesNotReferencedByRetainedArticles(uploader)).isZero();
    }

    @Test
    @DisplayName("綁定草稿／待審／被駁回文章的檔案：刪除（這些文章隨作者刪帳移除）")
    void fileBoundToRemovedStatusArticle_isDeleted() {
        UUID draft = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        FileMetadata f1 = file(UsageType.ARTICLE_CONTENT, draft);
        FileMetadata f2 = file(UsageType.ARTICLE_CONTENT, pending);
        FileMetadata f3 = file(UsageType.ARTICLE_COVER, rejected);
        stubFiles(f1, f2, f3);
        stubArticle(draft, "DRAFT");
        stubArticle(pending, "PENDING_REVIEW");
        stubArticle(rejected, "REJECTED");

        int deleted = service.deleteFilesNotReferencedByRetainedArticles(uploader);

        assertThat(deleted).isEqualTo(3);
        verify(fileService).deleteFile(f1.getId(), uploader, false);
        verify(fileService).deleteFile(f2.getId(), uploader, false);
        verify(fileService).deleteFile(f3.getId(), uploader, false);
    }

    @Test
    @DisplayName("綁定的文章已不存在：刪除（沒有任何保留文章引用它）")
    void fileBoundToMissingArticle_isDeleted() {
        UUID gone = UUID.randomUUID();
        FileMetadata orphan = file(UsageType.ARTICLE_CONTENT, gone);
        stubFiles(orphan);
        when(articleLookupFacade.findByUuid(gone)).thenReturn(Optional.empty());

        service.deleteFilesNotReferencedByRetainedArticles(uploader);

        verify(fileService).deleteFile(orphan.getId(), uploader, false);
    }

    @Test
    @DisplayName("頭像：一律刪除（使用者個資）")
    void avatar_isAlwaysDeleted() {
        FileMetadata avatar = file(UsageType.AVATAR, null);
        stubFiles(avatar);

        service.deleteFilesNotReferencedByRetainedArticles(uploader);

        verify(fileService).deleteFile(avatar.getId(), uploader, false);
    }

    @Test
    @DisplayName("多個檔案綁同一篇文章：每個不同的文章只查一次狀態（不逐檔查詢）")
    void sameArticle_isLookedUpOnce() {
        UUID article = UUID.randomUUID();
        stubFiles(file(UsageType.ARTICLE_CONTENT, article), file(UsageType.ARTICLE_CONTENT, article),
                file(UsageType.ARTICLE_COVER, article));
        stubArticle(article, "PUBLISHED");

        service.deleteFilesNotReferencedByRetainedArticles(uploader);

        verify(articleLookupFacade, times(1)).findByUuid(article);
    }

    @Test
    @DisplayName("沒有任何上傳檔案：不查文章、不刪除")
    void noFiles_doesNothing() {
        stubFiles();

        assertThat(service.deleteFilesNotReferencedByRetainedArticles(uploader)).isZero();
        verify(articleLookupFacade, never()).findByUuid(any());
    }

    @Test
    @DisplayName("某檔案已被刪除（FILE_NOT_FOUND）：視為已完成，繼續處理其餘檔案，不往外拋（重送冪等）")
    void fileAlreadyGone_isIgnored() {
        FileMetadata gone = file(UsageType.ARTICLE_CONTENT, null);
        FileMetadata remaining = file(UsageType.ARTICLE_CONTENT, null);
        stubFiles(gone, remaining);
        doThrow(new BusinessException(FileErrorCode.FILE_NOT_FOUND))
                .when(fileService).deleteFile(gone.getId(), uploader, false);

        int deleted = service.deleteFilesNotReferencedByRetainedArticles(uploader);

        assertThat(deleted).isEqualTo(1);
        verify(fileService).deleteFile(remaining.getId(), uploader, false);
    }

    @Test
    @DisplayName("某檔案刪除失敗（例如 MinIO 故障）：其餘檔案仍會處理，最後把例外往外拋（交由容器重試）")
    void deletionFailure_continuesThenThrows() {
        FileMetadata broken = file(UsageType.ARTICLE_CONTENT, null);
        FileMetadata healthy = file(UsageType.ARTICLE_CONTENT, null);
        stubFiles(broken, healthy);
        doThrow(new IllegalStateException("MinIO down"))
                .when(fileService).deleteFile(broken.getId(), uploader, false);

        assertThatThrownBy(() -> service.deleteFilesNotReferencedByRetainedArticles(uploader))
                .isInstanceOf(IllegalStateException.class).hasMessage("MinIO down");

        verify(fileService).deleteFile(healthy.getId(), uploader, false);
    }

    @Test
    @DisplayName("文章狀態查詢失敗：整體往外拋、不刪除任何檔案（寧可重試，也不在無法確認時銷毀仍被引用的圖片）")
    void articleLookupFailure_throwsWithoutDeletingAnything() {
        UUID article = UUID.randomUUID();
        stubFiles(file(UsageType.ARTICLE_CONTENT, null), file(UsageType.ARTICLE_CONTENT, article));
        when(articleLookupFacade.findByUuid(article)).thenThrow(new IllegalStateException("article down"));

        assertThatThrownBy(() -> service.deleteFilesNotReferencedByRetainedArticles(uploader))
                .isInstanceOf(IllegalStateException.class);

        verify(fileService, never()).deleteFile(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
