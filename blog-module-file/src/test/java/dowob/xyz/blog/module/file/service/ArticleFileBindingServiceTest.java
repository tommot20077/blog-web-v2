package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleFileBindingService} 單元測試（W5A／Q12：article→file 綁定改走 MQ，解析邏輯由 article 模組搬入）。
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleFileBindingService")
class ArticleFileBindingServiceTest {

    @Mock private FileService fileService;
    @Mock private ArticleLookupFacade articleLookupFacade;

    @InjectMocks private ArticleFileBindingService service;

    private static String url(UUID fileId) {
        return "/api/v1/files/" + fileId + "/content";
    }

    @Nested
    @DisplayName("extractFileUuids")
    class ExtractFileUuids {

        @Test
        @DisplayName("擷取內文所有 /api/v1/files/{uuid}/content 連結，去重並保留首次出現順序")
        void extractFileUuids_multipleLinks_dedupedInOrder() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            String content = "![a](" + url(a) + ")\n文字\n![b](" + url(b) + ")\n再貼一次 ![a](" + url(a) + ")";

            assertThat(ArticleFileBindingService.extractFileUuids(content)).containsExactly(a, b);
        }

        @Test
        @DisplayName("絕對網址形式（含網域）亦能擷取")
        void extractFileUuids_absoluteUrl_matches() {
            UUID a = UUID.randomUUID();

            assertThat(ArticleFileBindingService.extractFileUuids("![x](https://blog.example.com" + url(a) + ")"))
                    .containsExactly(a);
        }

        @Test
        @DisplayName("大寫 hex 的 UUID 亦能擷取")
        void extractFileUuids_uppercaseUuid_matches() {
            UUID a = UUID.randomUUID();

            String content = "![x](/api/v1/files/" + a.toString().toUpperCase() + "/content)";

            assertThat(ArticleFileBindingService.extractFileUuids(content)).containsExactly(a);
        }

        @Test
        @DisplayName("格式錯誤的 uuid、其他路徑、null、空白：安靜略過，回傳空清單")
        void extractFileUuids_malformedOrEmpty_returnsEmpty() {
            assertThat(ArticleFileBindingService.extractFileUuids("![x](/api/v1/files/not-a-uuid/content)")).isEmpty();
            assertThat(ArticleFileBindingService.extractFileUuids("![x](/api/v1/files/" + UUID.randomUUID() + "/metadata)")).isEmpty();
            assertThat(ArticleFileBindingService.extractFileUuids(null)).isEmpty();
            assertThat(ArticleFileBindingService.extractFileUuids("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("rebindFromArticleContent")
    class Rebind {

        @Test
        @DisplayName("文章存在：以最新內文解析出的檔案清單完整替換綁定")
        void rebind_articleExists_bindsFilesFromLatestContent() {
            UUID articleUuid = UUID.randomUUID();
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(articleLookupFacade.findContentByUuid(articleUuid))
                    .thenReturn(Optional.of("![a](" + url(a) + ") ![b](" + url(b) + ")"));

            boolean result = service.rebindFromArticleContent(articleUuid);

            assertThat(result).isTrue();
            verify(fileService).bindToArticle(articleUuid, List.of(a, b));
        }

        @Test
        @DisplayName("內文已無任何圖：以空清單綁定（解除先前殘留的綁定，避免權限殘留）")
        void rebind_contentWithoutImages_bindsEmptyList() {
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of(""));

            assertThat(service.rebindFromArticleContent(articleUuid)).isTrue();

            verify(fileService).bindToArticle(articleUuid, List.of());
        }

        @Test
        @DisplayName("文章已不存在：略過，不動任何綁定")
        void rebind_articleMissing_skips() {
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findContentByUuid(articleUuid)).thenReturn(Optional.empty());

            assertThat(service.rebindFromArticleContent(articleUuid)).isFalse();

            verify(fileService, never()).bindToArticle(any(), any());
        }

        @Test
        @DisplayName("articleUuid 為 null：略過，不查文章也不綁定")
        void rebind_nullArticleUuid_skips() {
            assertThat(service.rebindFromArticleContent(null)).isFalse();

            verify(fileService, never()).bindToArticle(any(), any());
        }

        @Test
        @DisplayName("綁定失敗：例外往外拋（交由 consumer 的冪等交易 rollback 並重試，不再吞掉）")
        void rebind_bindFails_propagates() {
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of("x"));
            doThrow(new IllegalStateException("db down")).when(fileService).bindToArticle(any(), any());

            assertThatThrownBy(() -> service.rebindFromArticleContent(articleUuid))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
