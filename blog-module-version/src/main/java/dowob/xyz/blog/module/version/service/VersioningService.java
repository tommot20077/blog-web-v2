package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.version.mapper.VersionMapper;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Version 模組核心 Service — 寫入 / 還原 / 配置。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class VersioningService {

    public static final String TYPE_AUTO = "AUTO";
    public static final String TYPE_MANUAL = "MANUAL";
    public static final String TYPE_PUBLISHED = "PUBLISHED";

    private final ArticleRepository articleRepo;
    private final ArticleVersionRepository versionRepo;
    private final VersionMapper versionMapper;
    private final PreferenceResolver preferenceResolver;

    /**
     * 記錄自動快照並套用滾動保留策略。
     * <p>若 articleId 不存在則靜默返回（冪等）。</p>
     *
     * @param articleId 文章主鍵
     */
    @Transactional
    public void recordAutoSnapshot(Long articleId) {
        Article article = articleRepo.findById(articleId).orElse(null);
        if (article == null) return;

        AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.getAuthorId());

        ArticleVersion v = snapshotFromArticle(article, TYPE_AUTO, null);
        versionRepo.save(v);

        versionMapper.retainAuto(articleId, cfg.retain());
    }

    /**
     * 把 article 當前狀態複製成一份 ArticleVersion（不寫入 DB）。
     * <p>供 manual / published / pre-restore 共用。</p>
     *
     * @param article 來源文章
     * @param type    快照類型（TYPE_AUTO / TYPE_MANUAL / TYPE_PUBLISHED）
     * @param note    備注說明（可為 null）
     * @return 尚未持久化的 ArticleVersion
     */
    protected ArticleVersion snapshotFromArticle(Article article, String type, String note) {
        ArticleVersion v = new ArticleVersion();
        v.setUuid(UUID.randomUUID());
        v.setArticleId(article.getId());
        v.setAuthorId(article.getAuthorId());
        v.setType(type);
        v.setTitle(article.getTitle());
        v.setSlug(article.getSlug());
        v.setContent(article.getContent());
        v.setSummary(article.getSummary());
        v.setCoverImageUrl(article.getCoverImageUrl());
        ArticleStatus st = article.getStatus();
        v.setStatus(st != null ? st.name() : null);
        // tags 暫由 mapper 從 article_tags 撈（T11 restore + freezePublished 細節補上）
        v.setNote(note);
        v.setCreatedAt(LocalDateTime.now());
        return v;
    }
}
