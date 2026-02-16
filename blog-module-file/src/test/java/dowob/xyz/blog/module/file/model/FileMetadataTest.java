package dowob.xyz.blog.module.file.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FileMetadata 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("FileMetadata 單元測試")
class FileMetadataTest {

    /**
     * 驗證 belongsTo：uploader 與傳入 userId 相同時應返回 true
     */
    @Test
    @DisplayName("belongsTo_withMatchingUserId_returnsTrue")
    void belongsTo_withMatchingUserId_returnsTrue() {
        UUID userId = UUID.randomUUID();
        FileMetadata metadata = new FileMetadata();
        metadata.setUploaderId(userId);

        assertThat(metadata.belongsTo(userId)).isTrue();
    }

    /**
     * 驗證 belongsTo：uploader 與傳入 userId 不同時應返回 false
     */
    @Test
    @DisplayName("belongsTo_withDifferentUserId_returnsFalse")
    void belongsTo_withDifferentUserId_returnsFalse() {
        FileMetadata metadata = new FileMetadata();
        metadata.setUploaderId(UUID.randomUUID());

        assertThat(metadata.belongsTo(UUID.randomUUID())).isFalse();
    }

    /**
     * 驗證 belongsTo：傳入 null 時應返回 false
     */
    @Test
    @DisplayName("belongsTo_withNullUserId_returnsFalse")
    void belongsTo_withNullUserId_returnsFalse() {
        FileMetadata metadata = new FileMetadata();
        metadata.setUploaderId(UUID.randomUUID());

        assertThat(metadata.belongsTo(null)).isFalse();
    }
}
