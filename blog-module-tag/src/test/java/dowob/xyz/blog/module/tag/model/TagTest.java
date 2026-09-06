package dowob.xyz.blog.module.tag.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tag 領域模型單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Tag 領域模型單元測試")
class TagTest {

    @Test
    @DisplayName("incrementUsage: 從零開始遞增，結果應為 1")
    void incrementUsage_fromZero_becomesOne() {
        Tag tag = new Tag();
        tag.incrementUsage();
        assertThat(tag.getUsageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("incrementUsage: 連續兩次遞增，結果應為 2")
    void incrementUsage_twice_becomesTwo() {
        Tag tag = new Tag();
        tag.incrementUsage();
        tag.incrementUsage();
        assertThat(tag.getUsageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("decrementUsage: 從 1 遞減，結果應為 0")
    void decrementUsage_fromOne_becomesZero() {
        Tag tag = new Tag();
        tag.incrementUsage();
        tag.decrementUsage();
        assertThat(tag.getUsageCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("decrementUsage: 從 0 遞減，結果應保持 0（邊界條件：不為負數）")
    void decrementUsage_fromZero_staysZero() {
        Tag tag = new Tag();
        tag.decrementUsage();
        assertThat(tag.getUsageCount()).isEqualTo(0);
    }
}
