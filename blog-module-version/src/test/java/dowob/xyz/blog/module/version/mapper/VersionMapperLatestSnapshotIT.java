package dowob.xyz.blog.module.version.mapper;

import dowob.xyz.blog.module.version.model.LatestSnapshotRef;
import dowob.xyz.blog.module.version.support.SeededVersionUsers;
import dowob.xyz.blog.module.version.support.VersionCleanupITSupport;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VersionMapper#findLatestRef} 與 {@link VersionMapper#findContentLength} 整合測試（真實 Postgres，PERF-21）。
 *
 * <p>重點是長度語意：自動快照的字元差距門檻原本以 Java {@code String.length()}（UTF-16 code unit）比較，
 * PostgreSQL {@code length()} 以 code point 計，兩者對補充平面字元（emoji 等）不同。
 * 本測試對一組代表性字串（ASCII、CJK、emoji、混合、空字串、組合字元）逐一斷言資料庫算出的長度
 * <b>等於</b> Java {@code String.length()}，確保改寫後快照觸發的邊界行為與舊行為完全一致。</p>
 */
@DisplayName("VersionMapper 最新快照參照／內容長度 整合測試（PERF-21）")
class VersionMapperLatestSnapshotIT extends VersionCleanupITSupport {

    @Autowired private VersionMapper versionMapper;

    private void insert(UUID articleUuid, String type, String content, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO article_versions (uuid, article_uuid, author_uuid, type, title, slug, content, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 't', 's', ?, 'DRAFT', ?)",
                UUID.randomUUID(), articleUuid, SeededVersionUsers.USER1_UUID, type, content, Timestamp.valueOf(createdAt));
    }

    /** 兩步合成：最新一筆的（參照, 長度）。 */
    private int lengthOfLatest(UUID article, String type) {
        LatestSnapshotRef ref = versionMapper.findLatestRef(article, type).orElseThrow();
        return versionMapper.findContentLength(ref.id()).orElseThrow();
    }

    @ParameterizedTest(name = "[{index}] 長度等於 Java String.length()：{0}")
    @ValueSource(strings = {
            "hello world",
            "中文內容測試，包含全形標點。",
            "😀",
            "😀😀😀 mixed 混合 🎉 text 𠀀",
            "𠀀𠀁𠀂",
            "é combining",
            "line1\nline2\r\n\ttab",
            "😀 surrogate pair literal",
            "a"
    })
    @DisplayName("內容長度與 Java String.length()（UTF-16 code unit）一致")
    void contentLength_equalsJavaStringLength(String content) {
        UUID article = UUID.randomUUID();
        insert(article, "AUTO", content, LocalDateTime.of(2026, 1, 1, 0, 0));

        assertThat(lengthOfLatest(article, "AUTO")).isEqualTo(content.length());
    }

    @Test
    @DisplayName("大文件（含 emoji）：長度仍一致")
    void contentLength_largeDocument() {
        UUID article = UUID.randomUUID();
        String content = ("段落 paragraph 😀 \n").repeat(5000);
        insert(article, "AUTO", content, LocalDateTime.of(2026, 1, 1, 0, 0));

        assertThat(lengthOfLatest(article, "AUTO")).isEqualTo(content.length());
    }

    @Test
    @DisplayName("取該文章該類型「最新」一筆（created_at 最大）；其他類型與其他文章不干擾")
    void returnsLatestOfRequestedTypeForArticleOnly() {
        UUID article = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        insert(article, "AUTO", "old", LocalDateTime.of(2026, 1, 1, 0, 0));
        insert(article, "AUTO", "newer-auto", LocalDateTime.of(2026, 1, 2, 0, 0));
        insert(article, "MANUAL", "manual-is-newest-but-other-type", LocalDateTime.of(2026, 1, 3, 0, 0));
        insert(other, "AUTO", "other-article-newest", LocalDateTime.of(2026, 1, 4, 0, 0));

        LatestSnapshotRef ref = versionMapper.findLatestRef(article, "AUTO").orElseThrow();

        assertThat(ref.createdAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 0, 0));
        assertThat(versionMapper.findContentLength(ref.id()).orElseThrow()).isEqualTo("newer-auto".length());
    }

    @Test
    @DisplayName("沒有該類型的快照 → empty")
    void noSnapshot_isEmpty() {
        UUID article = UUID.randomUUID();
        insert(article, "MANUAL", "x", LocalDateTime.of(2026, 1, 1, 0, 0));

        Optional<LatestSnapshotRef> ref = versionMapper.findLatestRef(article, "AUTO");

        assertThat(ref).isEmpty();
        assertThat(versionMapper.findContentLength(-1L)).as("不存在的主鍵").isEmpty();
    }

    @Test
    @DisplayName("SQL 形狀：取參照的查詢完全不碰 content；取長度的查詢是主鍵點查且不單獨選出 content")
    void sqlShapes() throws Exception {
        String refSql = String.join(" ", VersionMapper.class
                .getMethod("findLatestRef", UUID.class, String.class).getAnnotation(Select.class).value());
        assertThat(refSql).doesNotContain("*").doesNotContain("content");

        String lenSql = String.join(" ", VersionMapper.class
                .getMethod("findContentLength", Long.class).getAnnotation(Select.class).value());
        String select = lenSql.substring(0, lenSql.indexOf(" FROM "));
        assertThat(select).doesNotContain("*");
        assertThat(select).doesNotContainPattern("(?i)(^|,)\\s*content\\s*(,|$)");
        assertThat(lenSql).contains("WHERE id = ?".replace("?", "#{id}"));
    }
}
