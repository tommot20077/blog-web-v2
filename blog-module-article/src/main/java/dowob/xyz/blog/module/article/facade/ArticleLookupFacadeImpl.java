package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

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
 * 另依賴 {@link ArticleDataAssembler}（設計 §3.1：填入 authorUuid / seriesUuid）。它只依賴
 * {@code ArticleMapper} 與 {@code ObjectProvider<SeriesFacade>}（延遲取得），不含任何 service / facade 的建構子依賴，
 * 因此不會重新引入循環。
 * </p>
 *
 * <p>
 * <b>不得為本類別新增 service / facade 依賴</b>：它存在的唯一理由是打斷
 * article ⇄ file 模組的 Spring 建構子循環依賴（成因見 {@link ArticleLookupFacade} javadoc）。
 * 加上任何指回 {@code ArticleService} / {@code ArticleFileBinder} / {@code FileFacade}
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
}
