package dowob.xyz.blog.module.file.config;

import dowob.xyz.blog.module.file.model.UsageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FileProperties} 單元測試：預設值、依用途取單檔上限、綁定校正參數驗證。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("FileProperties 單元測試")
class FilePropertiesTest {

    private static FileProperties defaults() {
        return new FileProperties(Map.of(), Set.of(), null, null, null, null);
    }

    @Test
    @DisplayName("未設定時的預設值：100MP、文章圖片 20MB、頭像 5MB、近 3 天、保留 7 天、單次刪 500")
    void defaults_matchDecisions() {
        FileProperties p = defaults();

        assertThat(p.maxImagePixels()).isEqualTo(100_000_000L);
        assertThat(p.maxArticleImageSize()).isEqualTo(DataSize.ofMegabytes(20));
        assertThat(p.maxAvatarSize()).isEqualTo(DataSize.ofMegabytes(5));
        assertThat(p.bindingReconcile().recentArticleDays()).isEqualTo(3);
        assertThat(p.bindingReconcile().unboundFileRetentionDays()).isEqualTo(7);
        assertThat(p.bindingReconcile().maxDeletesPerRun()).isEqualTo(500);
    }

    @Test
    @DisplayName("maxFileSizeBytes：頭像用頭像上限，封面與內文圖片用文章圖片上限")
    void maxFileSizeBytes_dependsOnUsageType() {
        FileProperties p = defaults();

        assertThat(p.maxFileSizeBytes(UsageType.AVATAR)).isEqualTo(5L * 1024 * 1024);
        assertThat(p.maxFileSizeBytes(UsageType.ARTICLE_CONTENT)).isEqualTo(20L * 1024 * 1024);
        assertThat(p.maxFileSizeBytes(UsageType.ARTICLE_COVER)).isEqualTo(20L * 1024 * 1024);
    }

    @Test
    @DisplayName("綁定校正參數為非正數視為設定錯誤（避免 0 天保留把剛上傳的檔案全清掉）")
    void bindingReconcile_nonPositiveValues_rejected() {
        assertThatThrownBy(() -> new FileProperties.BindingReconcile(3, 0, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileProperties.BindingReconcile(-1, 7, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileProperties.BindingReconcile(3, 7, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
