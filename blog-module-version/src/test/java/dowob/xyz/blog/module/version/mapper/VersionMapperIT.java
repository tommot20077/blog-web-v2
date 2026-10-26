package dowob.xyz.blog.module.version.mapper;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.version.service.VersioningService;
import dowob.xyz.blog.module.version.support.VersionCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VersionMapper} 整合測試（真實 Postgres）：以「兩篇文章」驗證 V28 改寫的三條 SQL 只動到指定文章。
 *
 * <p>V28 把 {@code retainAuto}、{@code deleteAutoByArticle}、{@code countPublished} 的條件由
 * {@code article_id} 改為 {@code article_uuid}；若條件被漏掉，只有單一文章的測試無法察覺，
 * 因此每個案例都讓文章 B 先有 AUTO 與 PUBLISHED，操作文章 A 後斷言 B 的筆數不變。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("VersionMapper 整合測試（文章間隔離）")
class VersionMapperIT extends VersionCleanupITSupport {

    @Autowired private VersionMapper versionMapper;
    @Autowired private VersioningService versioningService;

    private int count(UUID articleUuid, String type) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM article_versions WHERE article_uuid = ? AND type = ?",
                Integer.class, articleUuid, type);
        return n == null ? 0 : n;
    }

    private void insertMany(UUID articleUuid, String type, int times) {
        for (int i = 0; i < times; i++) {
            insertVersion(articleUuid, type);
        }
    }

    @Test
    @DisplayName("retainAuto：只裁剪文章 A 超過保留數的 AUTO，文章 B 的 AUTO 與 PUBLISHED 不變")
    void retainAuto_trimsOnlyGivenArticle() {
        Article a = createArticle(ArticleStatus.DRAFT);
        Article b = createArticle(ArticleStatus.DRAFT);
        insertMany(a.getUuid(), "AUTO", 5);
        insertMany(a.getUuid(), "PUBLISHED", 1);
        insertMany(b.getUuid(), "AUTO", 4);
        insertMany(b.getUuid(), "PUBLISHED", 2);

        int deleted = versionMapper.retainAuto(a.getUuid(), 2);

        assertThat(deleted).isEqualTo(3);
        assertThat(count(a.getUuid(), "AUTO")).isEqualTo(2);
        assertThat(count(a.getUuid(), "PUBLISHED")).as("retainAuto 不動 PUBLISHED").isEqualTo(1);
        assertThat(count(b.getUuid(), "AUTO")).as("文章 B 的 AUTO 不得被牽動").isEqualTo(4);
        assertThat(count(b.getUuid(), "PUBLISHED")).isEqualTo(2);
    }

    @Test
    @DisplayName("deleteAutoByArticle：只清文章 A 的 AUTO，保留 A 的 PUBLISHED／MANUAL，文章 B 完全不變")
    void deleteAutoByArticle_clearsOnlyGivenArticlesAutos() {
        Article a = createArticle(ArticleStatus.DRAFT);
        Article b = createArticle(ArticleStatus.DRAFT);
        insertMany(a.getUuid(), "AUTO", 2);
        insertMany(a.getUuid(), "PUBLISHED", 1);
        insertMany(a.getUuid(), "MANUAL", 1);
        insertMany(b.getUuid(), "AUTO", 3);
        insertMany(b.getUuid(), "PUBLISHED", 2);

        int deleted = versionMapper.deleteAutoByArticle(a.getUuid());

        assertThat(deleted).isEqualTo(2);
        assertThat(count(a.getUuid(), "AUTO")).isZero();
        assertThat(count(a.getUuid(), "PUBLISHED")).isEqualTo(1);
        assertThat(count(a.getUuid(), "MANUAL")).isEqualTo(1);
        assertThat(count(b.getUuid(), "AUTO")).as("文章 B 的 AUTO 不得被牽動").isEqualTo(3);
        assertThat(count(b.getUuid(), "PUBLISHED")).isEqualTo(2);
    }

    @Test
    @DisplayName("countPublished：只計文章 A 自己的 PUBLISHED 數")
    void countPublished_countsOnlyGivenArticle() {
        Article a = createArticle(ArticleStatus.DRAFT);
        Article b = createArticle(ArticleStatus.DRAFT);
        insertMany(a.getUuid(), "PUBLISHED", 1);
        insertMany(a.getUuid(), "AUTO", 2);
        insertMany(b.getUuid(), "PUBLISHED", 3);

        assertThat(versionMapper.countPublished(a.getUuid())).isEqualTo(1);
        assertThat(versionMapper.countPublished(b.getUuid())).isEqualTo(3);
    }

    @Test
    @DisplayName("freezePublished（service）：文章 A 發布後備註為 v2 且 A 的 AUTO 清光，文章 B 的 AUTO 與 PUBLISHED 不變")
    void freezePublished_isolatedFromOtherArticles() {
        Article a = createArticle(ArticleStatus.DRAFT);
        Article b = createArticle(ArticleStatus.DRAFT);
        insertMany(a.getUuid(), "PUBLISHED", 1);
        insertMany(a.getUuid(), "AUTO", 2);
        insertMany(b.getUuid(), "AUTO", 3);
        insertMany(b.getUuid(), "PUBLISHED", 2);

        versioningService.freezePublished(a.getUuid());

        assertThat(count(a.getUuid(), "PUBLISHED")).isEqualTo(2);
        assertThat(count(a.getUuid(), "AUTO")).isZero();
        assertThat(jdbc.queryForObject("SELECT note FROM article_versions WHERE article_uuid = ? "
                + "AND type = 'PUBLISHED' ORDER BY created_at DESC LIMIT 1", String.class, a.getUuid()))
                .as("A 已有 1 筆 PUBLISHED，不受 B 的 2 筆影響").isEqualTo("Published v2");
        assertThat(count(b.getUuid(), "AUTO")).isEqualTo(3);
        assertThat(count(b.getUuid(), "PUBLISHED")).isEqualTo(2);
    }
}
