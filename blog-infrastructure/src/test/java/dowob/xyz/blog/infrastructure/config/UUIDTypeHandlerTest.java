package dowob.xyz.blog.infrastructure.config;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UUIDTypeHandler 單元測試
 *
 * <p>
 * 驗證 {@link UUIDTypeHandler} 各方法能正確將 UUID 與 JDBC String 互轉，
 * 以及 null 值的處理。採用純 Mockito 單元測試，不啟動 Spring Context。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UUIDTypeHandler 單元測試")
class UUIDTypeHandlerTest {

    /** 受測目標 */
    private UUIDTypeHandler handler;

    /** 模擬 PreparedStatement */
    @Mock
    private PreparedStatement preparedStatement;

    /** 模擬 ResultSet */
    @Mock
    private ResultSet resultSet;

    /** 模擬 CallableStatement */
    @Mock
    private CallableStatement callableStatement;

    /** 測試用 UUID 固定值 */
    private static final UUID TEST_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String TEST_UUID_STR = "550e8400-e29b-41d4-a716-446655440000";

    /**
     * 每個測試前初始化受測物件
     */
    @BeforeEach
    void setUp() {
        handler = new UUIDTypeHandler();
    }

    // ─── setNonNullParameter ───────────────────────────────────────────────────

    /**
     * 驗證 setNonNullParameter 以 ps.setObject(i, uuid) 方式設定參數
     */
    @Test
    @DisplayName("setNonNullParameter 應呼叫 ps.setObject(i, uuid)")
    void setNonNullParameter_callsPsSetObject() throws SQLException {
        handler.setNonNullParameter(preparedStatement, 1, TEST_UUID, JdbcType.OTHER);

        verify(preparedStatement).setObject(1, TEST_UUID);
    }

    // ─── getNullableResult(ResultSet, String) ─────────────────────────────────

    /**
     * 驗證 getNullableResult(RS, columnName) 對非 null 字串正確解析為 UUID
     */
    @Test
    @DisplayName("getNullableResult(RS, columnName) 非 null 字串應回傳對應 UUID")
    void getNullableResultByColumnName_withValidString_returnsUUID() throws SQLException {
        when(resultSet.getString("article_uuid")).thenReturn(TEST_UUID_STR);

        UUID result = handler.getNullableResult(resultSet, "article_uuid");

        assertThat(result).isEqualTo(TEST_UUID);
    }

    /**
     * 驗證 getNullableResult(RS, columnName) 對 null 字串回傳 null
     */
    @Test
    @DisplayName("getNullableResult(RS, columnName) null 字串應回傳 null")
    void getNullableResultByColumnName_withNull_returnsNull() throws SQLException {
        when(resultSet.getString("article_uuid")).thenReturn(null);

        UUID result = handler.getNullableResult(resultSet, "article_uuid");

        assertThat(result).isNull();
    }

    // ─── getNullableResult(ResultSet, int) ────────────────────────────────────

    /**
     * 驗證 getNullableResult(RS, columnIndex) 對非 null 字串正確解析為 UUID
     */
    @Test
    @DisplayName("getNullableResult(RS, columnIndex) 非 null 字串應回傳對應 UUID")
    void getNullableResultByColumnIndex_withValidString_returnsUUID() throws SQLException {
        when(resultSet.getString(1)).thenReturn(TEST_UUID_STR);

        UUID result = handler.getNullableResult(resultSet, 1);

        assertThat(result).isEqualTo(TEST_UUID);
    }

    /**
     * 驗證 getNullableResult(RS, columnIndex) 對 null 字串回傳 null
     */
    @Test
    @DisplayName("getNullableResult(RS, columnIndex) null 字串應回傳 null")
    void getNullableResultByColumnIndex_withNull_returnsNull() throws SQLException {
        when(resultSet.getString(1)).thenReturn(null);

        UUID result = handler.getNullableResult(resultSet, 1);

        assertThat(result).isNull();
    }

    // ─── getNullableResult(CallableStatement, int) ────────────────────────────

    /**
     * 驗證 getNullableResult(CS, columnIndex) 對非 null 字串正確解析為 UUID
     */
    @Test
    @DisplayName("getNullableResult(CS, columnIndex) 非 null 字串應回傳對應 UUID")
    void getNullableResultFromCallableStatement_withValidString_returnsUUID() throws SQLException {
        when(callableStatement.getString(2)).thenReturn(TEST_UUID_STR);

        UUID result = handler.getNullableResult(callableStatement, 2);

        assertThat(result).isEqualTo(TEST_UUID);
    }

    /**
     * 驗證 getNullableResult(CS, columnIndex) 對 null 字串回傳 null
     */
    @Test
    @DisplayName("getNullableResult(CS, columnIndex) null 字串應回傳 null")
    void getNullableResultFromCallableStatement_withNull_returnsNull() throws SQLException {
        when(callableStatement.getString(2)).thenReturn(null);

        UUID result = handler.getNullableResult(callableStatement, 2);

        assertThat(result).isNull();
    }
}
