package dowob.xyz.blog.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ClientIpResolver} 單元測試（SEC-01 第 3 層）。
 *
 * <p>
 * 驗證「由右往左數 {@code trustedProxyCount} 層」的語意：X-Forwarded-For 左側節點可由 client 任意偽造，
 * 右側節點由自家 proxy 逐層附加，故絕不可取最左節點；信任層數為 0 時完全忽略 XFF。
 * 本測試使用真實的 {@link MockHttpServletRequest}（而非 Mockito stub），
 * 才能驗證「多行 XFF header」這類 servlet 容器的實際行為。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
class ClientIpResolverTest {

    /** 直接對端位址（remoteAddr）：測試中代表最後一層 proxy 的位址 */
    private static final String REMOTE_ADDR = "10.0.0.5";

    /** 真實 client 位址（文件保留網段 TEST-NET-3） */
    private static final String REAL_CLIENT = "203.0.113.7";

    /** 攻擊者偽造的位址 */
    private static final String FORGED = "6.6.6.6";

    /**
     * 建立帶 remoteAddr 與（可選）單行 XFF 的請求。
     *
     * @param xff XFF header 值；null 表示不送此 header
     * @return 模擬請求
     */
    private static MockHttpServletRequest requestWith(String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(REMOTE_ADDR);
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        return request;
    }

    // =========================================================================
    // 信任層數 = 0（預設）：完全不信任 XFF
    // =========================================================================

    /** 驗證：count=0 時任何 XFF 都被忽略，回傳 remoteAddr（SEC-01 繞過已關的核心）。 */
    @Test
    @DisplayName("count=0 → 忽略 XFF，回傳 remoteAddr")
    void resolve_withZeroTrustedProxies_ignoresXff() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        assertThat(resolver.resolve(requestWith(FORGED))).isEqualTo(REMOTE_ADDR);
        assertThat(resolver.resolve(requestWith(FORGED + ", " + REAL_CLIENT))).isEqualTo(REMOTE_ADDR);
    }

    /** 驗證：count=0 且無 XFF 時回傳 remoteAddr。 */
    @Test
    @DisplayName("count=0 且無 XFF → 回傳 remoteAddr")
    void resolve_withZeroTrustedProxiesAndNoHeader_returnsRemoteAddr() {
        assertThat(new ClientIpResolver(0).resolve(requestWith(null))).isEqualTo(REMOTE_ADDR);
    }

    /** 驗證：count=0 時 remoteAddr 原樣回傳，不做正規化（IPv6 亦同）。 */
    @Test
    @DisplayName("count=0 → remoteAddr 原樣回傳（不改寫容器提供的值）")
    void resolve_withZeroTrustedProxies_returnsRemoteAddrUntouched() {
        MockHttpServletRequest request = requestWith(FORGED);
        request.setRemoteAddr("0:0:0:0:0:0:0:1");

        assertThat(new ClientIpResolver(0).resolve(request)).isEqualTo("0:0:0:0:0:0:0:1");
    }

    // =========================================================================
    // 信任層數 >= 1：由右往左數
    // =========================================================================

    /** 驗證：count=1 取 XFF 最右一節。 */
    @Test
    @DisplayName("count=1 → 取 XFF 最右一節")
    void resolve_withOneTrustedProxy_takesRightmost() {
        assertThat(new ClientIpResolver(1).resolve(requestWith(REAL_CLIENT))).isEqualTo(REAL_CLIENT);
    }

    /** 驗證：count=2 取 XFF 由右數第二節。 */
    @Test
    @DisplayName("count=2 → 取 XFF 由右數第二節")
    void resolve_withTwoTrustedProxies_takesSecondFromRight() {
        ClientIpResolver resolver = new ClientIpResolver(2);

        assertThat(resolver.resolve(requestWith(REAL_CLIENT + ", 10.42.0.1"))).isEqualTo(REAL_CLIENT);
    }

    /** 驗證：count=3 取由右數第三節。 */
    @Test
    @DisplayName("count=3 → 取 XFF 由右數第三節")
    void resolve_withThreeTrustedProxies_takesThirdFromRight() {
        ClientIpResolver resolver = new ClientIpResolver(3);

        assertThat(resolver.resolve(requestWith(REAL_CLIENT + ", 172.16.0.2, 10.42.0.1"))).isEqualTo(REAL_CLIENT);
    }

    /**
     * 驗證：偽造多段 XFF 無法繞過——攻擊者在鏈頭塞任意多節，count=1 仍取 proxy 附加的最右節。
     */
    @Test
    @DisplayName("偽造多段 XFF 前綴 + count=1 → 仍取 proxy 附加的最右節")
    void resolve_withForgedPrefix_countOne_stillTakesRightmost() {
        ClientIpResolver resolver = new ClientIpResolver(1);

        assertThat(resolver.resolve(requestWith(FORGED + ", 7.7.7.7, 8.8.8.8, " + REAL_CLIENT)))
                .isEqualTo(REAL_CLIENT);
    }

    /** 驗證：偽造多段 XFF 前綴 + count=2，取由右數第二節，而非任何偽造節。 */
    @Test
    @DisplayName("偽造多段 XFF 前綴 + count=2 → 取由右數第二節")
    void resolve_withForgedPrefix_countTwo_takesSecondFromRight() {
        ClientIpResolver resolver = new ClientIpResolver(2);

        assertThat(resolver.resolve(requestWith(FORGED + ", 7.7.7.7, " + REAL_CLIENT + ", 10.42.0.1")))
                .isEqualTo(REAL_CLIENT);
    }

    /**
     * 驗證：偽造的 XFF 不論長短，只要 count=1，結果與偽造內容無關（同一真實 client 得到同一 key）。
     */
    @Test
    @DisplayName("count=1 → 不同偽造前綴得到相同結果（限流 key 不可被換）")
    void resolve_differentForgedPrefixes_yieldSameResult() {
        ClientIpResolver resolver = new ClientIpResolver(1);

        String a = resolver.resolve(requestWith("1.1.1.1, " + REAL_CLIENT));
        String b = resolver.resolve(requestWith("2.2.2.2, 3.3.3.3, " + REAL_CLIENT));
        String c = resolver.resolve(requestWith(REAL_CLIENT));

        assertThat(a).isEqualTo(b).isEqualTo(c).isEqualTo(REAL_CLIENT);
    }

    /** 驗證：節點前後空白被移除。 */
    @Test
    @DisplayName("節點前後空白 → 被 trim")
    void resolve_entriesWithSurroundingWhitespace_areTrimmed() {
        ClientIpResolver resolver = new ClientIpResolver(1);

        assertThat(resolver.resolve(requestWith(FORGED + " ,   " + REAL_CLIENT + "  "))).isEqualTo(REAL_CLIENT);
    }

    /**
     * 驗證：同一請求帶多行 XFF header 時，視同以逗號串接（RFC 7230 §3.2.2），
     * 由右往左數跨行計算——不可只看第一行（那是 client 送的）。
     */
    @Test
    @DisplayName("多行 XFF header → 串接後由右往左數（不可只看第一行）")
    void resolve_multipleHeaderLines_areJoinedBeforeCounting() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(REMOTE_ADDR);
        request.addHeader("X-Forwarded-For", FORGED);
        request.addHeader("X-Forwarded-For", REAL_CLIENT);

        assertThat(new ClientIpResolver(1).resolve(request)).isEqualTo(REAL_CLIENT);
    }

    // =========================================================================
    // 缺漏／數量不足／格式錯誤：一律退回 remoteAddr（偏向「共用限流桶」而非「被繞過」）
    // =========================================================================

    /** 驗證：count>=1 但沒有 XFF，回傳 remoteAddr。 */
    @Test
    @DisplayName("count=1 且無 XFF → 回傳 remoteAddr")
    void resolve_withoutXff_returnsRemoteAddr() {
        assertThat(new ClientIpResolver(1).resolve(requestWith(null))).isEqualTo(REMOTE_ADDR);
    }

    /** 驗證：空字串與純空白的 XFF 視同不存在。 */
    @ParameterizedTest(name = "XFF=\"{0}\" → remoteAddr")
    @ValueSource(strings = {"", " ", "   ", "\t", ",", " , ,"})
    @DisplayName("空白／空字串／只有逗號的 XFF → 回傳 remoteAddr")
    void resolve_blankXff_returnsRemoteAddr(String xff) {
        assertThat(new ClientIpResolver(1).resolve(requestWith(xff))).isEqualTo(REMOTE_ADDR);
    }

    /**
     * 驗證：XFF 項目數少於信任層數時退回 remoteAddr。
     * 理由：項目不足代表請求沒有經過預期的 proxy 層數（拓撲與設定不符或有人繞過 proxy），
     * 此時 XFF 內任何一節（含最左節）都無法確認可信；remoteAddr 是唯一未被偽造的值。
     * 不取「最左可信」——最左節正是 client 自己送的值。
     */
    @Test
    @DisplayName("XFF 項目數少於信任層數 → 退回 remoteAddr（不取最左節）")
    void resolve_chainShorterThanTrustedCount_fallsBackToRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(3);

        assertThat(resolver.resolve(requestWith(FORGED))).isEqualTo(REMOTE_ADDR);
        assertThat(resolver.resolve(requestWith(FORGED + ", " + REAL_CLIENT))).isEqualTo(REMOTE_ADDR);
    }

    /** 驗證：被選中的那一節是空的（例如尾端多逗號）→ 退回 remoteAddr，不回傳空字串。 */
    @Test
    @DisplayName("被選中的節點為空 → 退回 remoteAddr")
    void resolve_selectedEntryEmpty_fallsBackToRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(1);

        assertThat(resolver.resolve(requestWith(REAL_CLIENT + ","))).isEqualTo(REMOTE_ADDR);
        assertThat(resolver.resolve(requestWith(REAL_CLIENT + ", ,"))).isEqualTo(REMOTE_ADDR);
    }

    /**
     * 驗證：被選中的那一節不是合法 IP 字面值（含 unknown、主機名稱、注入字元、位元組超範圍、帶埠）→ 退回 remoteAddr。
     * 回傳值會成為 Redis key 的一部分，且絕不可觸發 DNS 查詢。
     */
    @ParameterizedTest(name = "selected=\"{0}\" → remoteAddr")
    @ValueSource(strings = {
            "unknown", "example.com", "999.1.1.1", "1.2.3", "1.2.3.4.5", "01.02.03.04", "0x7f.0.0.1",
            "203.0.113.7:443", "[2001:db8::1]:443", "[2001:db8::1]", "1::2::3", "fe80::1%eth0",
            "2001:db8::zz", "abc:def", "'; DROP TABLE users;--", "203.0.113.7\r\nX-Evil: 1"
    })
    @DisplayName("被選中的節點格式錯誤 → 退回 remoteAddr")
    void resolve_selectedEntryMalformed_fallsBackToRemoteAddr(String bad) {
        assertThat(new ClientIpResolver(1).resolve(requestWith(FORGED + ", " + bad))).isEqualTo(REMOTE_ADDR);
    }

    /** 驗證：格式錯誤的節點只要不是被選中的那一節（位於偽造區），不影響結果。 */
    @Test
    @DisplayName("格式錯誤的節點位於偽造區 → 不影響結果")
    void resolve_malformedEntryOutsideSelection_isIgnored() {
        assertThat(new ClientIpResolver(1).resolve(requestWith("garbage, <script>, " + REAL_CLIENT)))
                .isEqualTo(REAL_CLIENT);
    }

    // =========================================================================
    // IPv6
    // =========================================================================

    /** 驗證：IPv6 位址被接受，並正規化為與 Tomcat remoteAddr 相同的完整展開小寫形式。 */
    @Test
    @DisplayName("IPv6 → 正規化為完整展開小寫形式")
    void resolve_ipv6_isNormalized() {
        ClientIpResolver resolver = new ClientIpResolver(1);

        assertThat(resolver.resolve(requestWith("2001:db8::1"))).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(resolver.resolve(requestWith("::1"))).isEqualTo("0:0:0:0:0:0:0:1");
    }

    /** 驗證：同一 IPv6 位址的不同寫法得到同一個結果（限流 key 不因大小寫／縮寫而分裂）。 */
    @Test
    @DisplayName("IPv6 不同寫法 → 同一結果")
    void resolve_ipv6Variants_yieldSameResult() {
        ClientIpResolver resolver = new ClientIpResolver(1);

        String compressed = resolver.resolve(requestWith("2001:DB8::1"));
        String expanded = resolver.resolve(requestWith("2001:0db8:0000:0000:0000:0000:0000:0001"));

        assertThat(compressed).isEqualTo(expanded).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    /** 驗證：IPv4-mapped IPv6 正規化為 IPv4，避免同一 client 兩種寫法各佔一個限流桶。 */
    @Test
    @DisplayName("IPv4-mapped IPv6 → 正規化為 IPv4")
    void resolve_ipv4MappedIpv6_isNormalizedToIpv4() {
        assertThat(new ClientIpResolver(1).resolve(requestWith("::ffff:203.0.113.7"))).isEqualTo(REAL_CLIENT);
    }

    /** 驗證：count=2 的鏈中混合 IPv4／IPv6 仍依位置取值。 */
    @Test
    @DisplayName("混合 IPv4／IPv6 鏈 + count=2 → 依位置取值")
    void resolve_mixedChain_countTwo() {
        assertThat(new ClientIpResolver(2).resolve(requestWith(FORGED + ", 2001:db8::7, 10.42.0.1")))
                .isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    // =========================================================================
    // 設定值
    // =========================================================================

    /** 驗證：負的信任層數是設定錯誤，啟動即失敗（不靜默當成 0）。 */
    @Test
    @DisplayName("count 為負數 → 建構時拋 IllegalArgumentException")
    void constructor_negativeCount_throws() {
        assertThatThrownBy(() -> new ClientIpResolver(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 驗證：未設定 {@code app.http.trusted-proxy-count} 時預設 0（不信任 XFF）。 */
    @Test
    @DisplayName("未設定 app.http.trusted-proxy-count → 預設 0（忽略 XFF）")
    void property_default_isZero() {
        new ApplicationContextRunner()
                .withUserConfiguration(ClientIpResolver.class)
                .run(ctx -> assertThat(ctx.getBean(ClientIpResolver.class).resolve(requestWith(REAL_CLIENT)))
                        .isEqualTo(REMOTE_ADDR));
    }

    /** 驗證：{@code app.http.trusted-proxy-count=1} 確實綁定到解析器。 */
    @Test
    @DisplayName("app.http.trusted-proxy-count=1 → 綁定並取 XFF 最右節")
    void property_one_isBound() {
        new ApplicationContextRunner()
                .withUserConfiguration(ClientIpResolver.class)
                .withPropertyValues("app.http.trusted-proxy-count=1")
                .run(ctx -> assertThat(ctx.getBean(ClientIpResolver.class)
                        .resolve(requestWith(FORGED + ", " + REAL_CLIENT))).isEqualTo(REAL_CLIENT));
    }
}
