package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.mapper.ArticleAuthorCleanupMapper;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ArticleLookupFacade 實作
 *
 * <p>
 * 「最小依賴」的文章唯讀查詢 Bean：<b>只注入 {@link ArticleRepository}</b>，
 * 語意與 {@code ArticleFacadeImpl#findByUuid} 等價
 * （後者一路 delegate 到 {@code ArticleQuerySubService.findByUuid}，最終同樣是
 * {@code articleRepository.findByUuid}），差別只在依賴閉包乾淨。
 * </p>
 *
 * <p>
 * 另依賴 {@link ArticleDataAssembler}（填入 authorUuid / seriesUuid，兩者皆直接取自文章欄位）。
 * 它沒有任何依賴（不含任何 service / facade / mapper 的建構子依賴），因此不會重新引入循環。
 * </p>
 *
 * <p>
 * <b>不得為本類別新增 service / facade 依賴</b>：它存在的唯一理由是打斷
 * article ⇄ file 模組的 Spring 建構子循環依賴（成因見 {@link ArticleLookupFacade} javadoc）。
 * 加上任何指回 {@code ArticleService} / {@code ArticleFacade} 等胖 Bean
 * 的依賴，都會讓環重新成立，而且單元測試（全 mock）抓不到，只有實際啟動應用程式才會現形。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class ArticleLookupFacadeImpl implements ArticleLookupFacade {

    /** 文章 Repository（本 Bean 唯一允許的依賴） */
    private final ArticleRepository articleRepository;

    /** ArticleData 組裝元件（只依賴 mapper 與 ObjectProvider，不會引入 service / facade 循環） */
    private final ArticleDataAssembler articleDataAssembler;

    /** 作者名下文章查詢 mapper（只讀 SQL，不引入 service / facade 依賴） */
    private final ArticleAuthorCleanupMapper articleAuthorCleanupMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ArticleData> findByUuid(UUID articleUuid) {
        if (articleUuid == null) {
            return Optional.empty();
        }
        return articleRepository.findByUuid(articleUuid).map(articleDataAssembler::toArticleData);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<UUID> findFileUuidsReferencedByRetainedArticles(UUID authorUuid) {
        if (authorUuid == null) {
            return Set.of();
        }
        /* 去留政策的唯一真相是 ArticleStatus：保留 = 不隨作者刪帳移除的狀態 */
        List<String> retainedStatuses = Arrays.stream(ArticleStatus.values())
                .filter(status -> !status.isRemovedWithAuthorAccount())
                .map(Enum::name)
                .toList();
        return articleAuthorCleanupMapper.findReferencedFileUuidsByAuthorAndStatuses(authorUuid, retainedStatuses)
                .stream().map(UUID::fromString).collect(Collectors.toSet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<String> findContentByUuid(UUID articleUuid) {
        if (articleUuid == null) {
            return Optional.empty();
        }
        return articleRepository.findByUuid(articleUuid)
                .map(article -> article.getContent() == null ? "" : article.getContent());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<UUID> findFileUuidsReferencedByAuthorArticles(UUID authorUuid) {
        if (authorUuid == null) {
            return Set.of();
        }
        /* 單篇刪除後作者其餘文章都還在：不分狀態，凡引用即不可刪 */
        List<String> allStatuses = Arrays.stream(ArticleStatus.values()).map(Enum::name).toList();
        return articleAuthorCleanupMapper.findReferencedFileUuidsByAuthorAndStatuses(authorUuid, allStatuses)
                .stream().map(UUID::fromString).collect(Collectors.toSet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<UUID> filterExistingUuids(Collection<UUID> uuids) {
        if (uuids == null) {
            return Set.of();
        }
        List<UUID> distinct = uuids.stream().filter(Objects::nonNull).distinct().toList();
        return BatchedQuery.queryInBatches(distinct, articleAuthorCleanupMapper::findExistingUuidsIn).stream()
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }
}
