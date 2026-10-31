package dowob.xyz.blog.module.user.service;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 判讀 PostgreSQL 唯一約束違反（SQLSTATE {@code 23505}）是撞到哪一條約束。
 *
 * <p><b>為什麼不用例外型別判斷</b>：user 模組以 Spring Data JDBC 寫入，併發落敗時拋出的是
 * {@code DbActionExecutionException}（一般 {@code RuntimeException}），真正的
 * {@code PSQLException} 藏在多層 {@code getCause()} 之後；而且光知道「重複」不夠，
 * 還要知道是 email、username 還是 nickname 才能回對應的業務錯誤碼。
 * 所以沿著 cause 鏈（與 {@link SQLException#getNextException()} 鏈）找出第一個 {@code 23505}，
 * 解析其訊息第一行的約束名稱。</p>
 *
 * <p><b>只解析訊息第一行</b>：PostgreSQL JDBC 驅動的訊息格式為
 * {@code ERROR: duplicate key value violates unique constraint "約束名"} 換行
 * {@code Detail: Key (欄位)=(使用者輸入的值) already exists.}。第二行含使用者輸入，
 * 不可被誤認為約束名稱；第一行取第一組雙引號內的文字，不依賴英文措辭（伺服器 locale 翻譯後引號仍在）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
final class UniqueConstraintViolations {

    /** {@code users.email} 的唯一約束名稱（V1 以 {@code UNIQUE} 內嵌宣告，PostgreSQL 自動命名） */
    static final String USERS_EMAIL = "users_email_key";

    /** {@code users.username} 的唯一約束名稱（V3 以 {@code UNIQUE} 內嵌宣告，PostgreSQL 自動命名） */
    static final String USERS_USERNAME = "users_username_key";

    /** {@code users.nickname} 的唯一約束名稱（V34） */
    static final String USERS_NICKNAME = "uq_users_nickname";

    /** 唯一約束違反的 SQLSTATE */
    private static final String UNIQUE_VIOLATION = "23505";

    /** 最多檢查的例外節點數，防止異常的循環引用造成無窮迴圈 */
    private static final int MAX_NODES = 32;

    /** 取第一組雙引號內的文字 */
    private static final Pattern QUOTED_NAME = Pattern.compile("\"([^\"]+)\"");

    private UniqueConstraintViolations() {
    }

    /**
     * 從例外鏈取出被違反的唯一約束名稱。
     *
     * @param throwable 寫入時捕捉到的例外，可為 {@code null}
     * @return 約束名稱；例外鏈中沒有 {@code 23505}、或訊息第一行取不到名稱時為 empty
     */
    static Optional<String> constraintNameOf(Throwable throwable) {
        if (throwable == null) {
            return Optional.empty();
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(throwable);
        while (!pending.isEmpty() && visited.size() < MAX_NODES) {
            Throwable current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLException sql) {
                Optional<String> name = constraintNameOfSqlException(sql);
                if (name.isPresent()) {
                    return name;
                }
                if (sql.getNextException() != null) {
                    pending.add(sql.getNextException());
                }
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
        }
        return Optional.empty();
    }

    /**
     * 解析單一 {@link SQLException}。
     *
     * @param sql SQL 例外
     * @return SQLSTATE 為 23505 且訊息第一行有雙引號名稱時回傳該名稱，否則 empty
     */
    private static Optional<String> constraintNameOfSqlException(SQLException sql) {
        if (!UNIQUE_VIOLATION.equals(sql.getSQLState()) || sql.getMessage() == null) {
            return Optional.empty();
        }
        String firstLine = sql.getMessage().lines().findFirst().orElse("");
        Matcher matcher = QUOTED_NAME.matcher(firstLine);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
