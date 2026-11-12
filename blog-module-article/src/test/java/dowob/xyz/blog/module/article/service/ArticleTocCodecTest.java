package dowob.xyz.blog.module.article.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.module.article.model.dto.response.TocEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArticleTocCodec 單元測試。
 *
 * <p>TOC 的 JSON 序列化/反序列化原先分別內嵌在 {@code ArticleCommandSubService}（寫）與
 * {@code ArticleResponseMapper}（讀）兩個 private 方法裡；版本還原路徑需要第三份，
 * 故抽成共用元件。本測試釘住「恆非 null、失敗一律退回空值、不拋例外」這組不變量。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleTocCodec")
class ArticleTocCodecTest {

    private final ArticleTocCodec codec = new ArticleTocCodec(new ObjectMapper());

    @Nested
    @DisplayName("serialize")
    class Serialize {

        @Test
        @DisplayName("正常：有條目時序列化為 JSON 陣列，含 id / text / level")
        void serialize_withEntries_returnsJsonArray() {
            String json = codec.serialize(List.of(
                    new TocEntry("heading-安裝步驟", "安裝步驟", 2),
                    new TocEntry("heading-前置需求", "前置需求", 3)));

            assertThat(json).contains("\"id\":\"heading-安裝步驟\"")
                    .contains("\"text\":\"安裝步驟\"")
                    .contains("\"level\":2")
                    .contains("\"level\":3");
        }

        @Test
        @DisplayName("邊界：null 清單回傳 \"[]\"（不可存 null，Spring Data JDBC 會覆寫既有值）")
        void serialize_nullList_returnsEmptyJsonArray() {
            assertThat(codec.serialize(null)).isEqualTo("[]");
        }

        @Test
        @DisplayName("邊界：空清單回傳 \"[]\"")
        void serialize_emptyList_returnsEmptyJsonArray() {
            assertThat(codec.serialize(List.of())).isEqualTo("[]");
        }
    }

    @Nested
    @DisplayName("deserialize")
    class Deserialize {

        @Test
        @DisplayName("正常：合法 JSON 陣列還原為 TocEntry 清單")
        void deserialize_validJson_returnsEntries() {
            List<TocEntry> entries = codec.deserialize(
                    "[{\"id\":\"heading-a\",\"text\":\"A\",\"level\":2}]");

            assertThat(entries).containsExactly(new TocEntry("heading-a", "A", 2));
        }

        @Test
        @DisplayName("邊界：null 回傳空清單")
        void deserialize_null_returnsEmptyList() {
            assertThat(codec.deserialize(null)).isEmpty();
        }

        @Test
        @DisplayName("邊界：空白字串回傳空清單")
        void deserialize_blank_returnsEmptyList() {
            assertThat(codec.deserialize("   ")).isEmpty();
        }

        @Test
        @DisplayName("異常：格式損毀的 JSON 回傳空清單而非拋例外（TOC 損毀不應讓文章讀不出來）")
        void deserialize_malformedJson_returnsEmptyListWithoutThrowing() {
            assertThat(codec.deserialize("{not json")).isEmpty();
        }
    }

    @Test
    @DisplayName("正常：serialize → deserialize 可完整往返")
    void serializeThenDeserialize_roundTrips() {
        List<TocEntry> original = List.of(
                new TocEntry("heading-x", "X", 2),
                new TocEntry("heading-y", "Y", 3));

        assertThat(codec.deserialize(codec.serialize(original))).isEqualTo(original);
    }
}
