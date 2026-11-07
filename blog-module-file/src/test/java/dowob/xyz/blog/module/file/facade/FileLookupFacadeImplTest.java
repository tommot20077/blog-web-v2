package dowob.xyz.blog.module.file.facade;

import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FileLookupFacadeImpl} 單元測試：頭像必須同時是 AVATAR 用途且由該使用者上傳。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("FileLookupFacadeImpl 單元測試")
class FileLookupFacadeImplTest {

    private final FileMetadataRepository repository = mock(FileMetadataRepository.class);
    private final FileLookupFacadeImpl facade = new FileLookupFacadeImpl(repository);
    private final UUID owner = UUID.randomUUID();

    private FileMetadata file(UsageType type, UUID uploader) {
        FileMetadata f = new FileMetadata();
        f.setId(UUID.randomUUID());
        f.setUsageType(type);
        f.setUploaderId(uploader);
        when(repository.findById(f.getId())).thenReturn(Optional.of(f));
        return f;
    }

    @Test
    @DisplayName("本人上傳的 AVATAR → true")
    void ownAvatar_true() {
        FileMetadata f = file(UsageType.AVATAR, owner);

        assertThat(facade.isAvatarOwnedBy(f.getId(), owner)).isTrue();
    }

    @Test
    @DisplayName("用途不是 AVATAR（文章內容／封面）→ false")
    void nonAvatarUsage_false() {
        assertThat(facade.isAvatarOwnedBy(file(UsageType.ARTICLE_CONTENT, owner).getId(), owner)).isFalse();
        assertThat(facade.isAvatarOwnedBy(file(UsageType.ARTICLE_COVER, owner).getId(), owner)).isFalse();
    }

    @Test
    @DisplayName("他人的 AVATAR → false")
    void othersAvatar_false() {
        FileMetadata f = file(UsageType.AVATAR, UUID.randomUUID());

        assertThat(facade.isAvatarOwnedBy(f.getId(), owner)).isFalse();
    }

    @Test
    @DisplayName("檔案不存在 → false；參數為 null → false 且不查 DB")
    void missingOrNull_false() {
        when(repository.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        assertThat(facade.isAvatarOwnedBy(UUID.randomUUID(), owner)).isFalse();

        FileLookupFacadeImpl fresh = new FileLookupFacadeImpl(mock(FileMetadataRepository.class));
        assertThat(fresh.isAvatarOwnedBy(null, owner)).isFalse();
        assertThat(fresh.isAvatarOwnedBy(UUID.randomUUID(), null)).isFalse();
    }
}
