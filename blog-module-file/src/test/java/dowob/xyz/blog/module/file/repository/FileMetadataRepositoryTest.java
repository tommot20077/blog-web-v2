package dowob.xyz.blog.module.file.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * FileMetadataRepository 煙霧測試
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("FileMetadataRepository 煙霧測試")
class FileMetadataRepositoryTest {

    /**
     * 驗證 Repository 介面存在且可被 mock
     */
    @Test
    @DisplayName("repository_interface_exists")
    void repository_interface_exists() {
        FileMetadataRepository repo = mock(FileMetadataRepository.class);
        assertThat(repo).isNotNull();
    }
}
