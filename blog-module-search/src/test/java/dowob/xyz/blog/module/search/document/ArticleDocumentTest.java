package dowob.xyz.blog.module.search.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleDocument.AuthorInfo} 的結構測試
 *
 * <p>ES 文件的作者資訊只允許存 {@code uuid}（Keyword）與 {@code nickname}（Text）：
 * Long 使用者主鍵屬於 user 模組內部識別，username 是登入識別符，兩者都不應落進搜尋索引。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleDocument.AuthorInfo 結構")
class ArticleDocumentTest {

    @Test
    @DisplayName("AuthorInfo 的欄位只有 uuid 與 nickname（不含 id／username）")
    void authorInfo_shouldOnlyDeclareUuidAndNickname() {
        String[] names = Arrays.stream(ArticleDocument.AuthorInfo.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(java.lang.reflect.Field::getName)
                .toArray(String[]::new);

        assertThat(names).containsExactlyInAnyOrder("uuid", "nickname");
    }

    @Test
    @DisplayName("AuthorInfo.uuid 為 Keyword、nickname 為 Text")
    void authorInfo_fieldTypes_shouldBeKeywordAndText() throws NoSuchFieldException {
        Field uuid = ArticleDocument.AuthorInfo.class.getDeclaredField("uuid").getAnnotation(Field.class);
        Field nickname = ArticleDocument.AuthorInfo.class.getDeclaredField("nickname").getAnnotation(Field.class);

        assertThat(uuid.type()).isEqualTo(FieldType.Keyword);
        assertThat(nickname.type()).isEqualTo(FieldType.Text);
    }

    @Test
    @DisplayName("ArticleDocument 有頂層 uuid 欄位：Keyword／String（供每日幽靈校正 search_after 排序；_id 不可排序）")
    void articleDocument_topLevelUuid_shouldBeKeywordString() throws NoSuchFieldException {
        java.lang.reflect.Field field = ArticleDocument.class.getDeclaredField("uuid");

        assertThat(field.getType()).isEqualTo(String.class);
        assertThat(field.getAnnotation(Field.class).type()).isEqualTo(FieldType.Keyword);
    }

    @Test
    @DisplayName("uuid 欄位型別為 String（ES 存 UUID 字串）")
    void authorInfo_uuid_shouldBeString() throws NoSuchFieldException {
        assertThat(ArticleDocument.AuthorInfo.class.getDeclaredField("uuid").getType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("B4：ES 文件不再有 viewCount／likeCount（計數與詳情頁同源，由 ArticleFacade 提供，不進索引）")
    void articleDocument_hasNoEngagementCounters() {
        assertThat(java.util.Arrays.stream(ArticleDocument.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("viewCount", "likeCount");
    }
}
