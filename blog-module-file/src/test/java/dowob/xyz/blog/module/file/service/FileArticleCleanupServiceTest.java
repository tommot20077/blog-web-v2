package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.FileErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.SystemException;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FileArticleCleanupService} 單元測試（DATA-07：刪除單篇文章後清理綁定該文章的檔案）。
 *
 * <p>去留判斷：綁定在已刪文章、非頭像、且<b>不被該上傳者其餘文章（任何狀態）的內文／封面引用</b>者刪除；
 * 實際刪除委派 {@link FileService#deleteFile}（先刪 MinIO 後刪 DB，MinIO 失敗即拋錯）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileArticleCleanupService")
class FileArticleCleanupServiceTest {

    @Mock private FileMetadataRepository fileMetadataRepository;
    @Mock private FileService fileService;
    @Mock private ArticleLookupFacade articleLookupFacade;

    private FileArticleCleanupService service;
    private final UUID article = UUID.randomUUID();
    private final UUID uploader = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new FileArticleCleanupService(fileMetadataRepository, fileService, articleLookupFacade);
    }

    private FileMetadata file(UUID uploaderId, UsageType usageType) {
        FileMetadata metadata = new FileMetadata();
        metadata.setId(UUID.randomUUID());
        metadata.setUploaderId(uploaderId);
        metadata.setUsageType(usageType);
        metadata.setArticleUuid(article);
        return metadata;
    }

    private void stubBound(FileMetadata... files) {
        when(fileMetadataRepository.findByArticleUuid(article)).thenReturn(List.of(files));
    }

    @Test
    @DisplayName("只被已刪文章綁定、其餘文章沒引用的檔案：以上傳者身分走 deleteFile（嚴格語意：先 MinIO 後 DB）")
    void unreferencedFile_isDeleted() {
        FileMetadata orphan = file(uploader, UsageType.ARTICLE_CONTENT);
        stubBound(orphan);
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(uploader)).thenReturn(Set.of());

        int deleted = service.deleteFilesBoundToDeletedArticle(article);

        assertThat(deleted).isEqualTo(1);
        verify(fileService).deleteFile(orphan.getId(), uploader, false);
    }

    @Test
    @DisplayName("同一張圖仍被作者其他文章（含草稿）的內文／封面引用：保留，不呼叫 deleteFile")
    void fileReferencedByAnotherArticle_isKept() {
        FileMetadata shared = file(uploader, UsageType.ARTICLE_CONTENT);
        FileMetadata orphan = file(uploader, UsageType.ARTICLE_COVER);
        stubBound(shared, orphan);
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(uploader)).thenReturn(Set.of(shared.getId()));

        int deleted = service.deleteFilesBoundToDeletedArticle(article);

        assertThat(deleted).isEqualTo(1);
        verify(fileService, never()).deleteFile(shared.getId(), uploader, false);
        verify(fileService).deleteFile(orphan.getId(), uploader, false);
    }

    @Test
    @DisplayName("頭像一律不動（頭像不綁文章，且是使用者個資）：即使 article_uuid 異常指向此文章，也不刪、不查引用")
    void avatar_isNeverDeleted() {
        FileMetadata avatar = file(uploader, UsageType.AVATAR);
        stubBound(avatar);

        int deleted = service.deleteFilesBoundToDeletedArticle(article);

        assertThat(deleted).isZero();
        verifyNoInteractions(fileService, articleLookupFacade);
    }

    @Test
    @DisplayName("沒有綁定此文章的檔案：回傳 0，不查引用、不刪除")
    void noBoundFiles_doesNothing() {
        when(fileMetadataRepository.findByArticleUuid(article)).thenReturn(List.of());

        assertThat(service.deleteFilesBoundToDeletedArticle(article)).isZero();
        verifyNoInteractions(fileService, articleLookupFacade);
    }

    @Test
    @DisplayName("articleUuid 為 null：略過，不查 DB")
    void nullArticle_doesNothing() {
        assertThat(service.deleteFilesBoundToDeletedArticle(null)).isZero();
        verifyNoInteractions(fileMetadataRepository, fileService, articleLookupFacade);
    }

    @Test
    @DisplayName("不同上傳者的檔案：每位上傳者各查一次其餘文章的引用，互不影響")
    void filesOfDifferentUploaders_areEvaluatedPerUploader() {
        UUID otherUploader = UUID.randomUUID();
        FileMetadata mine = file(uploader, UsageType.ARTICLE_CONTENT);
        FileMetadata theirs = file(otherUploader, UsageType.ARTICLE_CONTENT);
        stubBound(mine, theirs);
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(uploader)).thenReturn(Set.of());
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(otherUploader)).thenReturn(Set.of(theirs.getId()));

        int deleted = service.deleteFilesBoundToDeletedArticle(article);

        assertThat(deleted).isEqualTo(1);
        verify(fileService).deleteFile(mine.getId(), uploader, false);
        verify(fileService, never()).deleteFile(theirs.getId(), otherUploader, false);
    }

    @Test
    @DisplayName("MinIO 刪除失敗（STORAGE_ERROR）：例外往外拋供 MQ 重試；其餘檔案仍會嘗試刪除")
    void minioFailure_rethrowsAfterTryingTheRest() {
        FileMetadata failing = file(uploader, UsageType.ARTICLE_CONTENT);
        FileMetadata fine = file(uploader, UsageType.ARTICLE_CONTENT);
        stubBound(failing, fine);
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(uploader)).thenReturn(Set.of());
        doThrow(new SystemException(CommonErrorCode.STORAGE_ERROR)).when(fileService).deleteFile(failing.getId(), uploader, false);

        assertThatThrownBy(() -> service.deleteFilesBoundToDeletedArticle(article)).isInstanceOf(SystemException.class);

        verify(fileService).deleteFile(fine.getId(), uploader, false);
    }

    @Test
    @DisplayName("檔案已被併發刪除（FILE_NOT_FOUND，例如重送）：視為已完成，不當成失敗")
    void alreadyDeletedFile_isTreatedAsDone() {
        FileMetadata gone = file(uploader, UsageType.ARTICLE_CONTENT);
        stubBound(gone);
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(uploader)).thenReturn(Set.of());
        doThrow(new BusinessException(FileErrorCode.FILE_NOT_FOUND)).when(fileService).deleteFile(gone.getId(), uploader, false);

        assertThat(service.deleteFilesBoundToDeletedArticle(article)).isZero();
    }

    @Test
    @DisplayName("fail-safe：查詢其餘文章引用失敗時整體拋出，不刪除任何檔案（無法確認時寧可重試）")
    void referenceLookupFailure_deletesNothingAndRethrows() {
        stubBound(file(uploader, UsageType.ARTICLE_CONTENT));
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(uploader)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.deleteFilesBoundToDeletedArticle(article)).isInstanceOf(IllegalStateException.class);

        verify(fileService, never()).deleteFile(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
