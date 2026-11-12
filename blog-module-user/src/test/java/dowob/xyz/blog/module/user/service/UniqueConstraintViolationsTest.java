package dowob.xyz.blog.module.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UniqueConstraintViolations} 單元測試：從例外鏈判讀 PostgreSQL 唯一約束名稱。
 *
 * <p>真實的 PostgreSQL 例外由 {@code UserRaceConditionIntegrationTest} 驗證；
 * 本測試以相同格式的 {@link SQLException} 訊息驗證解析規則與邊界。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("UniqueConstraintViolations 唯一約束判讀")
class UniqueConstraintViolationsTest {

    /**
     * 建立與 PostgreSQL JDBC 驅動訊息格式相同的唯一約束違反例外。
     *
     * @param constraint 約束名稱
     * @param detail     第二行 Detail 內容
     * @return 帶 SQLState 23505 的 SQLException
     */
    private static SQLException uniqueViolation(String constraint, String detail) {
        return new SQLException("ERROR: duplicate key value violates unique constraint \"" + constraint + "\"\n"
                + "  Detail: " + detail, "23505");
    }

    @Test
    @DisplayName("Spring 的 DuplicateKeyException 包著 23505 → 取得約束名稱")
    void duplicateKeyException_wrappingUniqueViolation_returnsConstraintName() {
        Throwable e = new DuplicateKeyException("insert failed",
                uniqueViolation("users_email_key", "Key (email)=(a@b.c) already exists."));

        assertThat(UniqueConstraintViolations.constraintNameOf(e)).contains("users_email_key");
    }

    @Test
    @DisplayName("多層包裝（例如 Spring Data JDBC 的 DbActionExecutionException）也能找到")
    void deeplyWrappedViolation_isFound() {
        Throwable e = new RuntimeException("Failed to execute DbAction.InsertRoot",
                new DataIntegrityViolationException("wrapped",
                        uniqueViolation("uq_users_nickname", "Key (nickname)=(Yuan) already exists.")));

        assertThat(UniqueConstraintViolations.constraintNameOf(e)).contains("uq_users_nickname");
    }

    @Test
    @DisplayName("只解析訊息第一行：Detail 中出現的雙引號內容不會被誤認為約束名稱")
    void quotedTextInDetailLine_isIgnored() {
        Throwable e = new DuplicateKeyException("insert failed",
                uniqueViolation("users_username_key", "Key (username)=(\"uq_users_nickname\") already exists."));

        assertThat(UniqueConstraintViolations.constraintNameOf(e)).contains("users_username_key");
    }

    @Test
    @DisplayName("不是 23505（例如外鍵違反 23503）→ empty")
    void otherSqlState_returnsEmpty() {
        Throwable e = new DataIntegrityViolationException("fk",
                new SQLException("ERROR: insert or update on table \"x\" violates foreign key constraint \"x_fkey\"",
                        "23503"));

        assertThat(UniqueConstraintViolations.constraintNameOf(e)).isEmpty();
    }

    @Test
    @DisplayName("23505 但訊息第一行沒有雙引號 → empty（不猜測）")
    void uniqueViolationWithoutQuotedName_returnsEmpty() {
        Throwable e = new DuplicateKeyException("insert failed", new SQLException("duplicate key", "23505"));

        assertThat(UniqueConstraintViolations.constraintNameOf(e)).isEmpty();
    }

    @Test
    @DisplayName("例外鏈中沒有 SQLException，或傳入 null → empty")
    void noSqlException_orNull_returnsEmpty() {
        assertThat(UniqueConstraintViolations.constraintNameOf(new IllegalStateException("boom"))).isEmpty();
        assertThat(UniqueConstraintViolations.constraintNameOf(null)).isEmpty();
    }

    @Test
    @DisplayName("SQLException 的 getNextException 鏈也會被檢查（JDBC 批次違反時約束資訊可能在其中）")
    void nextExceptionChain_isInspected() {
        SQLException head = new SQLException("batch failed", "23000");
        head.setNextException(uniqueViolation("users_email_key", "Key (email)=(x) already exists."));
        Throwable e = new DuplicateKeyException("insert failed", head);

        assertThat(UniqueConstraintViolations.constraintNameOf(e)).contains("users_email_key");
    }
}
