package dowob.xyz.blog.module.file.facade;

import dowob.xyz.blog.module.file.service.FileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * FileFacadeImpl 單元測試
 *
 * <p>
 * 只驗證委派行為（B3 範圍）：{@code bindFilesToArticle} 是否以相同參數呼叫
 * {@link FileService#bindToArticle(UUID, List)} 恰好一次。
 * 授權矩陣與「完整替換語意」本身的邏輯已由 {@code FileServiceTest}（B2）覆蓋，此處不重複。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileFacadeImpl 單元測試")
class FileFacadeImplTest {

    @Mock
    private FileService fileService;

    @InjectMocks
    private FileFacadeImpl fileFacadeImpl;

    /** ----------------------------------------------------------------------- */
    /** bindFilesToArticle                                                      */
    /** ----------------------------------------------------------------------- */

    @Nested
    @DisplayName("bindFilesToArticle")
    class BindFilesToArticleTests {

        @Test
        @DisplayName("正常：非空清單，以相同參數委派 FileService.bindToArticle 恰好一次")
        void bindFilesToArticle_withFileUuids_delegatesToFileServiceOnce() {
            UUID articleUuid = UUID.randomUUID();
            List<UUID> fileUuids = List.of(UUID.randomUUID(), UUID.randomUUID());

            fileFacadeImpl.bindFilesToArticle(articleUuid, fileUuids);

            verify(fileService).bindToArticle(articleUuid, fileUuids);
            verifyNoMoreInteractions(fileService);
        }

        @Test
        @DisplayName("邊界：空清單原樣透傳，不做特殊處理（FileService 已定義空清單=解除全部綁定）")
        void bindFilesToArticle_withEmptyList_passesThroughUnchanged() {
            UUID articleUuid = UUID.randomUUID();
            List<UUID> emptyList = List.of();

            fileFacadeImpl.bindFilesToArticle(articleUuid, emptyList);

            verify(fileService).bindToArticle(articleUuid, emptyList);
            verifyNoMoreInteractions(fileService);
        }

        @Test
        @DisplayName("防護：null 清單原樣透傳，不擅自轉換或拋 NPE（FileService 已定義 null=視為空清單）")
        void bindFilesToArticle_withNullList_passesThroughNullUnchanged() {
            UUID articleUuid = UUID.randomUUID();

            fileFacadeImpl.bindFilesToArticle(articleUuid, null);

            verify(fileService).bindToArticle(articleUuid, null);
            verifyNoMoreInteractions(fileService);
        }
    }
}
