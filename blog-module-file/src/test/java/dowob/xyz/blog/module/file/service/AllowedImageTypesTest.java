package dowob.xyz.blog.module.file.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AllowedImageTypes 白名單單元測試（FILE-03）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("AllowedImageTypes 白名單")
class AllowedImageTypesTest {

    @ParameterizedTest
    @CsvSource({"image/jpeg,jpg", "image/png,png", "image/gif,gif", "image/webp,webp"})
    @DisplayName("extensionFor_whitelistedMime_returnsFixedExtension")
    void extensionFor_whitelistedMime_returnsFixedExtension(String mime, String ext) {
        assertThat(AllowedImageTypes.extensionFor(mime)).contains(ext);
    }

    @Test
    @DisplayName("extensionFor_unknownOrNullMime_returnsEmpty")
    void extensionFor_unknownOrNullMime_returnsEmpty() {
        assertThat(AllowedImageTypes.extensionFor("image/svg+xml")).isEmpty();
        assertThat(AllowedImageTypes.extensionFor("application/x-php")).isEmpty();
        assertThat(AllowedImageTypes.extensionFor(null)).isEmpty();
    }
}
