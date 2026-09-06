package dowob.xyz.blog.common.api.request;

/**
 * 分頁查詢參數。
 *
 * <p><b>存在理由</b>：分頁 {@code size} 由 client 決定大小，未夾界時
 * {@code size=100000} 會讓查詢層拉進十萬筆資料，並使 {@code IN (...)} 的
 * bind parameter 數量失控。把夾界寫在 controller 是「呼叫端記得做」，
 * 漏一個端點就破一個；寫在本 record 的 compact constructor 則是
 * <b>型別本身持有這個不變式</b>——record 欄位為 final，且所有建構路徑
 * 都必須經過 canonical constructor，語法上沒有繞過的可能。</p>
 *
 * <p><b>與原 {@code @RequestParam} 的契約差異</b>：query string 參數名不變
 * （仍為 {@code page} / {@code size}），前端無須改動。元件型別採
 * {@link Integer} 而非 {@code int}，是為了區分「未提供」（{@code null}）
 * 與「明確傳 0」（夾為 1）——若用 {@code int}，未提供會綁成 0，
 * 而 0 與「沒傳」是不同語意。</p>

 * <p><b>預設值不由本型別決定</b>：現行 8 個分頁端點的 {@code defaultValue}
 * 並不一致（文章／搜尋為 10，留言／收藏／系列／版本為 20），統一之即為 API
 * 語意變更。故本型別只負責<b>上限與下限</b>這個安全不變式，預設值由各端點
 * 以 {@link #sizeOrDefault(int)} 提供。</p>
 *
 * <p>經 compact constructor 正規化後，{@link #page()} 與 {@link #size()}
 * <b>保證非 null 且落在合法範圍</b>，呼叫端無須再自行夾界。</p>
 *
 * @param page 頁碼，自 1 起；{@code null} 或小於 1 一律正規化為 1
 * @param size 每頁筆數；{@code null} 表示 client 未提供（請用 {@link #sizeOrDefault(int)}
 *             取值），其餘夾在 {@code [1, MAX_SIZE]}
 * @author Yuan
 * @version 1.0
 */
public record PageQuery(Integer page, Integer size) {

    /**
     * 每頁筆數上限。
     *
     * <p>取 1000 而非更小值，是為了不改變現行前端行為——前端文章列表目前即以
     * {@code size=1000} 取全量後自行分頁。更嚴格的上限（如 100）需先由前端改為
     * 真正的伺服器端分頁，否則會造成資料靜默遺失，見 findings {@code SEC-04}。</p>
     */
    public static final int MAX_SIZE = 1000;

    /**
     * 正規化分頁參數。
     *
     * <p>compact constructor 的參數為區域變數，對其賦值會被編譯器自動補上的
     * {@code this.x = x} 採用，因此此處的夾界即為最終存入欄位的值。</p>
     */
    public PageQuery {
        page = (page == null || page < 1) ? 1 : page;
        size = (size == null) ? null : Math.min(Math.max(size, 1), MAX_SIZE);
    }

    /**
     * 取每頁筆數，client 未提供時採端點自訂的預設值。
     *
     * <p>{@code fallback} 同樣會被夾界，避免端點自己寫出超過 {@link #MAX_SIZE}
     * 的預設值而繞過上限。</p>
     *
     * @param fallback 該端點的預設每頁筆數
     * @return 夾界後的每頁筆數，保證落在 {@code [1, MAX_SIZE]}
     */
    public int sizeOrDefault(int fallback) {
        return size == null ? Math.min(Math.max(fallback, 1), MAX_SIZE) : size;
    }
}
