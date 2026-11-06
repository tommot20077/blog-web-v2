package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
import dowob.xyz.blog.module.article.model.dto.request.ArticleListQuery;
import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 文章列表讀取路徑必須在「唯讀交易」內執行（PERF-29）。
 *
 * <p>以真實 Spring 代理驗證，而不只檢查 annotation：列表流程中的協作者（{@code UserFacade}、{@code SeriesFacade}）
 * 被呼叫的當下，記錄 {@link TransactionSynchronizationManager#isCurrentTransactionReadOnly()}。
 * 若 annotation 標在代理無法攔截的位置（例如 private 方法、self-invocation），本測試會失敗，
 * 單純反射檢查 annotation 則無法發現。詳情路徑刻意不開交易（見 {@code ArticleServiceImpl} JavaDoc），一併斷言。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("文章列表讀取路徑唯讀交易 IT（PERF-29）")
class ArticleListReadOnlyTransactionIT extends ArticleSeriesITSupport {

    @Autowired private ArticleQueryService queryService;
    @Autowired private ArticleQuerySubService querySubService;
    @Autowired private ArticleServiceImpl articleService;

    /** 協作者被呼叫當下的交易旗標：active:readOnly */
    private final List<String> observed = new ArrayList<>();

    @BeforeEach
    void stubCollaborators() {
        reset(userFacade);
        observed.clear();
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenAnswer(inv -> {
            observed.add(flags());
            return Map.of();
        });
        when(seriesFacade.batchGetSeriesBasicInfoByUuids(anySet())).thenAnswer(inv -> {
            observed.add(flags());
            return Map.<UUID, SeriesBasicInfo>of();
        });
    }

    private static String flags() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                + ":" + TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    private UUID seedSeriesArticle(String status, UUID author) {
        UUID series = UUID.randomUUID();
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, series_uuid, "
                        + "series_position, published_at) VALUES (?, ?, 't', ?, 'x', ?, ?, 1, now())",
                uuid, author, "slug-" + uuid, status, series);
        return uuid;
    }

    @Test
    @DisplayName("ArticleQuerySubService.getPublishedArticles：執行當下在唯讀交易內")
    void subService_getPublishedArticles_runsInReadOnlyTransaction() {
        seedSeriesArticle("PUBLISHED", UUID.randomUUID());

        querySubService.getPublishedArticles(ArticleListQuery.unfiltered(), 1, 10);

        assertThat(observed).containsExactly("true:true");
    }

    @Test
    @DisplayName("ArticleQuerySubService.getMyArticles／getPendingArticles：執行當下在唯讀交易內")
    void subService_myAndPending_runInReadOnlyTransaction() {
        UUID author = UUID.randomUUID();
        seedSeriesArticle("PENDING_REVIEW", author);

        querySubService.getMyArticles(author, 1, 10, null);
        querySubService.getPendingArticles(1, 10);

        assertThat(observed).containsExactly("true:true", "true:true");
    }

    @Test
    @DisplayName("ArticleQueryService 三個列表方法：含 enrich 在內整段都在唯讀交易內")
    void queryService_listMethods_runInReadOnlyTransaction() {
        UUID author = UUID.randomUUID();
        seedSeriesArticle("PENDING_REVIEW", author);
        seedSeriesArticle("PUBLISHED", author);

        queryService.getPublishedArticles(ArticleListQuery.unfiltered(), 1, 10);
        queryService.getMyArticles(author, 1, 10, null);
        queryService.getPendingArticles(1, 10);

        // 每個方法：子服務查作者（userFacade）＋ enrich 查系列（seriesFacade）→ 共 6 次，全部必須是唯讀交易
        assertThat(observed).hasSize(6).containsOnly("true:true");
    }

    @Test
    @DisplayName("詳情路徑刻意不開交易：ArticleServiceImpl 的詳情方法沒有 @Transactional（recordView 會發 MQ）")
    void detailPath_staysNonTransactional() throws Exception {
        assertThat(ArticleServiceImpl.class
                .getMethod("getArticleByUuid", UUID.class, UUID.class, dowob.xyz.blog.common.api.enums.Role.class, String.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(ArticleServiceImpl.class
                .getMethod("getArticleBySlug", String.class, UUID.class, dowob.xyz.blog.common.api.enums.Role.class, String.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        // 類別層級也不得標（否則整個詳情路徑被包進交易）
        assertThat(ArticleServiceImpl.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(articleService).isNotNull();
    }
}
