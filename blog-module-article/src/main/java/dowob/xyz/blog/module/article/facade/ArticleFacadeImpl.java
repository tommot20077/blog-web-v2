package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.event.TagInfo;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ArticleFacade 實作
 *
 * <p>
 * 提供跨模組的文章查詢能力，供搜尋模組取得全量已發布文章以建立 Elasticsearch 索引。
 * 依賴 ArticleMapper 執行資料庫查詢，UserFacade 補充作者資訊。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class ArticleFacadeImpl implements ArticleFacade {

    /**
     * 文章 MyBatis Mapper
     */
    private final ArticleMapper articleMapper;

    /**
     * 用戶 Facade（跨模組查詢作者資訊）
     */
    private final UserFacade userFacade;

    /**
     * 查詢所有已發布文章的索引資料
     *
     * @return 已發布文章的索引資料列表
     */
    @Override
    public List<ArticleIndexData> findAllPublishedForIndex() {
        return articleMapper.findAllPublished().stream()
                .map(this::toIndexData)
                .toList();
    }

    /**
     * 將文章實體轉換為索引資料 DTO
     *
     * @param article 文章實體
     * @return ArticleIndexData
     */
    private ArticleIndexData toIndexData(Article article) {
        List<TagInfo> tags = articleMapper.findTagsByArticleId(article.getId());
        List<ArticleIndexData.TagData> tagData = tags.stream()
                .map(t -> new ArticleIndexData.TagData(t.id(), t.name(), t.slug()))
                .toList();

        return new ArticleIndexData(
                article.getUuid(),
                article.getTitle(),
                article.getSlug(),
                article.getSummary(),
                stripMarkdown(article.getContent()),
                article.getAuthorId(),
                userFacade.getUserUsernameById(article.getAuthorId()).orElse(null),
                userFacade.getUserNicknameById(article.getAuthorId()).orElse(null),
                article.getPublishedAt(),
                article.getViewCount(),
                article.getLikeCount(),
                tagData);
    }

    /**
     * 去除 Markdown 格式，回傳純文字（供 Elasticsearch 索引）
     *
     * @param markdown Markdown 原文
     * @return 純文字內容
     */
    private String stripMarkdown(String markdown) {
        if (markdown == null) return "";
        return markdown
                .replaceAll("```[\\s\\S]*?```", "")
                .replaceAll("`[^`]*`", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\*{1,2}([^*]+)\\*{1,2}", "$1")
                .replaceAll("_{1,2}([^_]+)_{1,2}", "$1")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^[-*_]{3,}$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }
}
