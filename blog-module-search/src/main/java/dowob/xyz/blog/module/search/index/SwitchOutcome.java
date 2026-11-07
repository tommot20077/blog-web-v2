package dowob.xyz.blog.module.search.index;

/**
 * alias 切換請求回報失敗後，ES 端的實際結果（審查 M3）。
 *
 * <p>切換請求可能「回報失敗但其實已套用」（例如 socket 逾時，ES 已處理完）。此時若無條件刪除新索引，
 * alias 會隨索引一起消失，下一次寫入會被 ES 自動建成 dynamic mapping 的同名實體索引，搜尋全壞；
 * 從實體索引遷移時舊索引已被 remove_index，資料也一起沒了。因此回報失敗後一律先確認實際狀態。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public enum SwitchOutcome {

    /** alias 只指向新索引：切換其實已套用，視為成功 */
    APPLIED,

    /** alias 不指向新索引：切換確實沒有套用，可以安全刪除新索引 */
    NOT_APPLIED,

    /** 無法判斷（ES 不可達，或 alias 同時指向新索引與其他索引）：不刪任何索引，需人工檢查 */
    UNKNOWN
}
