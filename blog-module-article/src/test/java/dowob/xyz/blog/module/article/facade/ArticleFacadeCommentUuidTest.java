package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleFacadeImpl} 供 comment 模組使用的 UUID 版方法單元測試：
 * {@code existsByUuid}、{@code incrementCommentCount(UUID)}、{@code decrementCommentCount(UUID)}。
 *
 * <p>這裡只驗證 facade 層接線（委派、null 防護）；SQL 語意（不分狀態、守衛 &gt; 0）由
 * {@link ArticleFacadeCommentUuidIT} 對真實 PostgreSQL 驗證。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleFacadeImpl 留言用 UUID 版方法")
class ArticleFacadeCommentUuidTest {

    @Mock private ArticleMapper articleMapper;
    @InjectMocks private ArticleFacadeImpl facade;

    @Test
    @DisplayName("existsByUuid 委派 mapper，存在回傳 true")
    void existsByUuid_whenMapperReportsExists_returnsTrue() {
        UUID uuid = UUID.randomUUID();
        when(articleMapper.existsByUuid(uuid)).thenReturn(true);

        assertThat(facade.existsByUuid(uuid)).isTrue();
    }

    @Test
    @DisplayName("existsByUuid 查無時回傳 false")
    void existsByUuid_whenMapperReportsMissing_returnsFalse() {
        UUID uuid = UUID.randomUUID();
        when(articleMapper.existsByUuid(uuid)).thenReturn(false);

        assertThat(facade.existsByUuid(uuid)).isFalse();
    }

    @Test
    @DisplayName("existsByUuid 傳入 null 回傳 false 且不查 DB")
    void existsByUuid_whenNull_returnsFalseWithoutQuery() {
        assertThat(facade.existsByUuid(null)).isFalse();

        verify(articleMapper, never()).existsByUuid(any());
    }

    @Test
    @DisplayName("incrementCommentCount(UUID) 委派 mapper 的 UUID 版原子 +1")
    void incrementCommentCountByUuid_delegatesToMapper() {
        UUID uuid = UUID.randomUUID();

        facade.incrementCommentCount(uuid);

        verify(articleMapper).incrementCommentCountByUuid(uuid);
    }

    @Test
    @DisplayName("decrementCommentCount(UUID) 委派 mapper 的 UUID 版原子 -1")
    void decrementCommentCountByUuid_delegatesToMapper() {
        UUID uuid = UUID.randomUUID();

        facade.decrementCommentCount(uuid);

        verify(articleMapper).decrementCommentCountByUuid(uuid);
    }
}
