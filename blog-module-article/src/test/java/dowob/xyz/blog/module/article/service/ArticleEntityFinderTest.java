package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleEntityFinderTest {

    @Mock
    private ArticleRepository articleRepository;

    private ArticleEntityFinder finder;

    @BeforeEach
    void setUp() {
        finder = new ArticleEntityFinder(articleRepository);
    }

    @Test
    @DisplayName("findByUuidOrThrow returns article when it exists")
    void findByUuidOrThrow_existing_returnsArticle() {
        UUID uuid = UUID.randomUUID();
        Article article = new Article();
        article.setId(100L);
        article.setUuid(uuid);
        when(articleRepository.findByUuid(uuid)).thenReturn(Optional.of(article));

        Article result = finder.findByUuidOrThrow(uuid);

        assertThat(result).isSameAs(article);
    }

    @Test
    @DisplayName("findByUuidOrThrow throws ARTICLE_NOT_FOUND when missing")
    void findByUuidOrThrow_notFound_throws() {
        UUID uuid = UUID.randomUUID();
        when(articleRepository.findByUuid(uuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> finder.findByUuidOrThrow(uuid))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo(ArticleErrorCode.ARTICLE_NOT_FOUND.getCode());
    }
}
