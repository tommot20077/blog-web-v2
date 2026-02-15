package dowob.xyz.blog.module.tag.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository 介面存在性冒煙測試
 *
 * <p>
 * 驗證 {@link TagRepository}、{@link ArticleTagRepository}、
 * {@link UserTagFollowRepository} 介面可被正常 Mock，
 * 確認介面定義正確無誤。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Repository 介面存在性冒煙測試")
class RepositorySmokeTest {

    /**
     * Mock TagRepository
     */
    @Mock
    private TagRepository tagRepository;

    /**
     * Mock ArticleTagRepository
     */
    @Mock
    private ArticleTagRepository articleTagRepository;

    /**
     * Mock UserTagFollowRepository
     */
    @Mock
    private UserTagFollowRepository userTagFollowRepository;

    @Test
    @DisplayName("TagRepository 介面可被 Mock 建立")
    void tagRepository_canBeMocked() {
        assertThat(tagRepository).isNotNull();
    }

    @Test
    @DisplayName("ArticleTagRepository 介面可被 Mock 建立")
    void articleTagRepository_canBeMocked() {
        assertThat(articleTagRepository).isNotNull();
    }

    @Test
    @DisplayName("UserTagFollowRepository 介面可被 Mock 建立")
    void userTagFollowRepository_canBeMocked() {
        assertThat(userTagFollowRepository).isNotNull();
    }
}
