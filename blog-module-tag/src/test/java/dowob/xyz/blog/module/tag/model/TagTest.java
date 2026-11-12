package dowob.xyz.blog.module.tag.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tag 領域模型單元測試
 *
 * @author Yuan
 * @version 1.1
 */
@DisplayName("Tag 領域模型單元測試")
class TagTest {

    @Test
    @DisplayName("usageCount 只由重算寫入：Tag 不提供遞增／遞減 API（DATA-01 後這兩個方法是零呼叫的死碼，已刪除）")
    void tag_doesNotExposeIncrementalUsageApi() {
        assertThat(Arrays.stream(Tag.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("incrementUsage", "decrementUsage");
    }
}
