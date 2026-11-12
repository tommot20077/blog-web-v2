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
 * {@link CommentMapper#findFirstRepliesByParentIds} 的 parent_uuid 取得方式（PERF-22 中不需決策的部分）與每串前 N 則（D3）。
 *
 * <p>原本對每一列執行相關子查詢 {@code (SELECT p.uuid FROM comments p WHERE p.id = c.parent_id)}，
 * 改為 {@code LEFT JOIN comments p ON p.id = c.parent_id}。這是純 SQL 等價改寫：本測試先釘住結果
 * （每則回覆的 {@code parentUuid} 等於其父留言的 uuid、順序不變、軟刪除 leaf 仍被過濾），
 * 再用 SQL 形狀斷言確認已不使用相關子查詢。</p>
 */
@DisplayName("CommentMapper 回覆查詢：parent_uuid（PERF-22）、每串前 N 則與回覆分頁（D3）")
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

        List<CommentRow> rows = mapper.findFirstRepliesByParentIds(List.of(parentB.getId(), parentA.getId()), 100);

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

        List<CommentRow> rows = mapper.findFirstRepliesByParentIds(List.of(parent.getId()), 100);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUuid()).isEqualTo(reply.getUuid());
        assertThat(rows.get(0).getParentUuid()).isEqualTo(parent.getUuid());
    }

    @Test
    @DisplayName("沒有回覆的父留言 → 空清單；頂層查詢的 parentUuid 維持 null")
    void noReplies_isEmpty_andTopLevelParentUuidIsNull() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        Comment top = createComment(article, null);

        assertThat(mapper.findFirstRepliesByParentIds(List.of(top.getId()), 100)).isEmpty();
        List<CommentRow> topRows = mapper.findTopLevelByArticle(article.getUuid(), "oldest", 10, 0);
        assertThat(topRows).extracting(CommentRow::getParentUuid).containsOnlyNulls();
        assertThat(topRows).extracting(CommentRow::getUuid).containsExactly(top.getUuid());
    }

    @Test
    @DisplayName("SQL 形狀：不使用相關子查詢取 parent uuid，且前 N 則由 LATERAL + LIMIT 在資料庫內完成")
    void sql_usesLeftJoin_notCorrelatedSubquery() throws Exception {
        String sql = String.join(" ", CommentMapper.class
                .getMethod("findFirstRepliesByParentIds", List.class, int.class)
                .getAnnotation(Select.class).value()).replaceAll("\\s+", " ");

        assertThat(sql).as("parent uuid 以 JOIN 取得，不用逐列相關子查詢").doesNotContain("(SELECT p.uuid");
        assertThat(sql).contains("p.uuid AS parent_uuid");
        assertThat(sql).as("每串前 N 則在 SQL 內以 LATERAL ... LIMIT 完成，不撈全部再於 Java 截斷")
                .contains("JOIN LATERAL").contains("LIMIT #{perParent}");
    }

    @Test
    @DisplayName("D3：每個頂層各取前 N 則（5 則回覆取 3、4 則回覆取 3、2 則回覆全取），依 created_at 升冪，已刪除者不佔名額")
    void firstReplies_limitedPerParent_inCreationOrder_deletedExcluded() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        Comment parentA = createComment(article, null);
        Comment parentB = createComment(article, null);
        Comment parentC = createComment(article, null);
        Comment a1 = createComment(article, parentA.getId());
        Comment aDeleted = createComment(article, parentA.getId());
        Comment a2 = createComment(article, parentA.getId());
        Comment a3 = createComment(article, parentA.getId());
        createComment(article, parentA.getId());
        createComment(article, parentA.getId());
        Comment b1 = createComment(article, parentB.getId());
        Comment b2 = createComment(article, parentB.getId());
        Comment b3 = createComment(article, parentB.getId());
        createComment(article, parentB.getId());
        Comment c1 = createComment(article, parentC.getId());
        Comment c2 = createComment(article, parentC.getId());
        jdbc.update("UPDATE comments SET deleted_at = now() WHERE id = ?", aDeleted.getId());

        List<CommentRow> rows = mapper.findFirstRepliesByParentIds(
                List.of(parentA.getId(), parentB.getId(), parentC.getId()), 3);

        assertThat(rows).extracting(CommentRow::getUuid).containsExactly(
                a1.getUuid(), a2.getUuid(), a3.getUuid(),
                b1.getUuid(), b2.getUuid(), b3.getUuid(),
                c1.getUuid(), c2.getUuid());
    }

    @Test
    @DisplayName("D3：countRepliesByParentIds 只算未刪除的回覆；沒有回覆的頂層不出現在結果")
    void countReplies_excludesDeleted_andOmitsParentsWithoutReplies() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        Comment parentA = createComment(article, null);
        Comment parentB = createComment(article, null);
        Comment parentNone = createComment(article, null);
        for (int i = 0; i < 5; i++) {
            createComment(article, parentA.getId());
        }
        Comment gone = createComment(article, parentA.getId());
        createComment(article, parentB.getId());
        jdbc.update("UPDATE comments SET deleted_at = now() WHERE id = ?", gone.getId());

        Map<Long, Long> counts = mapper.countRepliesByParentIds(
                        List.of(parentA.getId(), parentB.getId(), parentNone.getId()))
                .stream().collect(Collectors.toMap(CommentMapper.ReplyCountRow::parentId,
                        CommentMapper.ReplyCountRow::replyCount));

        assertThat(counts).containsEntry(parentA.getId(), 5L).containsEntry(parentB.getId(), 1L)
                .doesNotContainKey(parentNone.getId());
    }

    @Test
    @DisplayName("D3：findReplyPage 依 created_at 升冪分頁（limit／offset），已刪除不回傳；countRepliesByParentId 為未刪除總數")
    void replyPage_pagesInOrder_andCountsLive() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        Comment parent = createComment(article, null);
        Comment other = createComment(article, null);
        Comment r1 = createComment(article, parent.getId());
        Comment rGone = createComment(article, parent.getId());
        Comment r2 = createComment(article, parent.getId());
        Comment r3 = createComment(article, parent.getId());
        Comment r4 = createComment(article, parent.getId());
        createComment(article, other.getId());
        jdbc.update("UPDATE comments SET deleted_at = now() WHERE id = ?", rGone.getId());

        assertThat(mapper.findReplyPage(parent.getId(), 2, 0)).extracting(CommentRow::getUuid)
                .containsExactly(r1.getUuid(), r2.getUuid());
        assertThat(mapper.findReplyPage(parent.getId(), 2, 2)).extracting(CommentRow::getUuid)
                .containsExactly(r3.getUuid(), r4.getUuid());
        assertThat(mapper.findReplyPage(parent.getId(), 2, 4)).isEmpty();
        assertThat(mapper.findReplyPage(parent.getId(), 2, 0)).extracting(CommentRow::getParentUuid)
                .containsOnly(parent.getUuid());
        assertThat(mapper.countRepliesByParentId(parent.getId())).isEqualTo(4);
    }
}
