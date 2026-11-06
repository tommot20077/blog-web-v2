package dowob.xyz.blog.module.comment.mapper;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.CommentRow;
import dowob.xyz.blog.module.comment.support.CommentCleanupITSupport;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommentMapper#findRepliesByParentIds} 的 parent_uuid 取得方式（PERF-22 中不需決策的部分）。
 *
 * <p>原本對每一列執行相關子查詢 {@code (SELECT p.uuid FROM comments p WHERE p.id = c.parent_id)}，
 * 改為 {@code LEFT JOIN comments p ON p.id = c.parent_id}。這是純 SQL 等價改寫：本測試先釘住結果
 * （每則回覆的 {@code parentUuid} 等於其父留言的 uuid、順序不變、軟刪除 leaf 仍被過濾），
 * 再用 SQL 形狀斷言確認已不使用相關子查詢。</p>
 */
@DisplayName("CommentMapper.findRepliesByParentIds 的 parent_uuid（PERF-22）")
class CommentMapperRepliesIT extends CommentCleanupITSupport {

    @Autowired private CommentMapper mapper;

    @Test
    @DisplayName("每則回覆的 parentUuid 等於其父留言 uuid；依 parent_id、created_at 排序；已軟刪除的 reply 被過濾")
    void replies_carryCorrectParentUuid_inStableOrder() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        Comment parentA = createComment(article, null);
        Comment parentB = createComment(article, null);
        Comment a1 = createComment(article, parentA.getId());
        Comment a2 = createComment(article, parentA.getId());
        Comment b1 = createComment(article, parentB.getId());
        Comment deletedLeaf = createComment(article, parentB.getId());
        jdbc.update("UPDATE comments SET deleted_at = now() WHERE id = ?", deletedLeaf.getId());

        List<CommentRow> rows = mapper.findRepliesByParentIds(List.of(parentB.getId(), parentA.getId()));

        assertThat(rows).extracting(CommentRow::getUuid)
                .as("依 parent_id 再 created_at；軟刪除 leaf 不出現")
                .containsExactly(a1.getUuid(), a2.getUuid(), b1.getUuid());
        Map<java.util.UUID, java.util.UUID> parentOf = rows.stream()
                .collect(Collectors.toMap(CommentRow::getUuid, CommentRow::getParentUuid));
        assertThat(parentOf.get(a1.getUuid())).isEqualTo(parentA.getUuid());
        assertThat(parentOf.get(a2.getUuid())).isEqualTo(parentA.getUuid());
        assertThat(parentOf.get(b1.getUuid())).isEqualTo(parentB.getUuid());
        assertThat(rows).extracting(CommentRow::getParentId)
                .containsExactly(parentA.getId(), parentA.getId(), parentB.getId());
    }

    @Test
    @DisplayName("父留言已軟刪除（tombstone）時，回覆的 parentUuid 仍然是該父留言 uuid")
    void parentUuid_stillResolved_whenParentIsSoftDeleted() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        Comment parent = createComment(article, null);
        Comment reply = createComment(article, parent.getId());
        jdbc.update("UPDATE comments SET deleted_at = now() WHERE id = ?", parent.getId());

        List<CommentRow> rows = mapper.findRepliesByParentIds(List.of(parent.getId()));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUuid()).isEqualTo(reply.getUuid());
        assertThat(rows.get(0).getParentUuid()).isEqualTo(parent.getUuid());
    }

    @Test
    @DisplayName("沒有回覆的父留言 → 空清單；頂層查詢的 parentUuid 維持 null")
    void noReplies_isEmpty_andTopLevelParentUuidIsNull() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        Comment top = createComment(article, null);

        assertThat(mapper.findRepliesByParentIds(List.of(top.getId()))).isEmpty();
        List<CommentRow> topRows = mapper.findTopLevelByArticle(article.getUuid(), "oldest", 10, 0);
        assertThat(topRows).extracting(CommentRow::getParentUuid).containsOnlyNulls();
        assertThat(topRows).extracting(CommentRow::getUuid).containsExactly(top.getUuid());
    }

    @Test
    @DisplayName("SQL 形狀：以 LEFT JOIN 取得 parent uuid，不再使用逐列執行的相關子查詢")
    void sql_usesLeftJoin_notCorrelatedSubquery() throws Exception {
        String sql = String.join(" ", CommentMapper.class
                .getMethod("findRepliesByParentIds", List.class)
                .getAnnotation(Select.class).value()).replaceAll("\\s+", " ");

        assertThat(sql).contains("LEFT JOIN comments p ON p.id = c.parent_id");
        assertThat(sql).doesNotContain("(SELECT p.uuid");
        assertThat(sql).contains("p.uuid AS parent_uuid");
    }
}
