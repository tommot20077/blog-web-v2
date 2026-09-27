package dowob.xyz.blog.common.api.request;

/**
 * 分頁查詢參數。
 *
 * <p><b>存在理由</b>：分頁 {@code size} 由 client 決定大小，未設界時
 * {@code size=100000} 會讓查詢層拉進十萬筆資料，並使 {@code IN (...)} 的
 * bind parameter 數量失控。把界限寫在 controller 是「呼叫端記得做」，
 * 漏一個端點就破一個；寫在本 record 的 compact constructor 則是
 * <b>型別本身持有這個不變式</b>——record 欄位為 final，且所有建構路徑
 * 都必須經過 canonical constructor，語法上沒有繞過的可能。</p>
 *
 * <p><b>超出範圍一律拒絕，不靜默修正</b>（SEC-04，Yuan 2026-09-27 決定）：
 * 原本 {@code size} 超過上限會被夾成上限、{@code page} / {@code size} 小於 1 會被改成 1。
 * 靜默夾界的風險在於 client 以為拿到了全部——前端文章列表即以 {@code size=1000}
 * 取全量，上限一旦調低就會無聲地少資料。改為拒絕後，錯誤在第一次呼叫就浮現。</p>
 *
 * <p><b>HTTP 層如何回 400</b>：compact constructor 丟出的例外若發生在 Spring 的
 * record 建構子綁定中，會被包成 {@code BeanInstantiationException} 而成為 500。
 * 故 HTTP 請求一律由 {@link PageQueryArgumentResolver} 解析：它在建構前處理
 * 非整數輸入，並把本型別的 {@link IllegalArgumentException} 轉成 {@code BusinessException}
 * → {@code GlobalExceptionHandler} → 400。解析器註冊於 {@code WebMvcConfig}。</p>
 *
 * <p><b>與原 {@code @RequestParam} 的契約差異</b>：query string 參數名不變
 * （仍為 {@code page} / {@code size}）。元件型別採 {@link Integer} 而非 {@code int}，
 * 是為了區分「未提供」（{@code null}）與「明確傳 0」（拒絕）。</p>
 *
 * <p><b>預設值不由本型別決定</b>：現行 8 個分頁端點的預設值並不一致
 * （文章／搜尋為 10，留言／收藏／系列／版本為 20），統一之即為 API 語意變更。
 * 故本型別只負責<b>合法範圍</b>這個安全不變式，預設值由各端點以
 * {@link #sizeOrDefault(int)} 提供。</p>
 *
 * @param page 頁碼，自 1 起；{@code null} 表示未提供，視為 1；小於 1 拒絕
 * @param size 每頁筆數；{@code null} 表示未提供（請用 {@link #sizeOrDefault(int)} 取值），
 *             須落在 {@code [1, MAX_SIZE]}，否則拒絕
 * @author Yuan
 * @version 1.0
 */
public record PageQuery(Integer page, Integer size) {

    /**
     * 每頁筆數上限。
     *
     * <p>取 1000 而非更小值，是為了不改變現行前端行為——前端文章列表目前即以
     * {@code size=1000} 取全量後自行分頁。更嚴格的上限（如 100）需先由前端改為
     * 真正的伺服器端分頁，否則前端會開始收到 400，見 findings {@code SEC-04}。</p>
     */
    public static final int MAX_SIZE = 1000;

    /**
     * 驗證分頁參數。
     *
     * <p>compact constructor 的參數為區域變數，對其賦值會被編譯器自動補上的
     * {@code this.x = x} 採用，因此 {@code page} 未提供時的 1 即為最終存入欄位的值。</p>
     *
     * @throws IllegalArgumentException page 小於 1，或 size 不在 {@code [1, MAX_SIZE]}；
     *                                  訊息會原樣回給 client，故以繁體中文撰寫
     */
    public PageQuery {
        if (page == null) {
            page = 1;
        } else if (page < 1) {
            throw new IllegalArgumentException("page 必須大於或等於 1");
        }
        if (size != null && (size < 1 || size > MAX_SIZE)) {
            throw new IllegalArgumentException("size 必須介於 1 與 " + MAX_SIZE + " 之間");
        }
    }

    /**
     * 取每頁筆數，client 未提供時採端點自訂的預設值。
     *
     * <p>{@code fallback} 由程式碼決定而非 client 輸入，故採夾界而非拒絕，
     * 避免端點自己寫出超過 {@link #MAX_SIZE} 的預設值而繞過上限。</p>
     *
     * @param fallback 該端點的預設每頁筆數
     * @return 每頁筆數，保證落在 {@code [1, MAX_SIZE]}
     */
    public int sizeOrDefault(int fallback) {
        return size == null ? Math.min(Math.max(fallback, 1), MAX_SIZE) : size;
    }
}
