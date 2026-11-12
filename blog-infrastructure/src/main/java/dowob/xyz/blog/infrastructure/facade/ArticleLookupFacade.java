package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 文章「最小依賴」查詢 Facade 介面
 *
 * <p>
 * 專供「本身位於 {@link ArticleFacade} 依賴鏈下游」的模組使用，只提供 article 元資料的唯讀查詢。
 * 實作（{@code ArticleLookupFacadeImpl}）僅注入 {@code ArticleRepository}，
 * 依賴閉包中不含任何 article 模組的 service / facade / 跨模組 Facade，因此不會把呼叫端拉回環中。
 * </p>
 *
 * <h3>為什麼需要與 {@link ArticleFacade} 分開？</h3>
 * <p>
 * article 模組與 file 模組曾互相需要對方的資料，形成 Spring 建構子循環依賴
 * （歷史成因：article 儲存文章後同步呼叫 file 綁定檔案，file 授權又需要 article 的元資料）。
 * 打斷方式是把 file 模組所需的最小查詢（「這篇文章的作者是誰／目前狀態／內文」）切出成本介面，
 * 由依賴閉包乾淨的 Bean 實作，環自然消失——比照 {@code SeriesFacadeImpl}「重構依賴鏈使環不成立」的既有慣例
 * （本專案零個 {@code @Lazy}、也未開啟 {@code spring.main.allow-circular-references}）。
 * </p>
 * <p>
 * W5A／Q12 起，「檔案 → 文章」綁定的回填改由 file 模組消費 {@code ArticleContentChangedEvent} 非同步完成，
 * article 模組不再依賴 file 模組的任何綁定類別；file 模組仍透過本介面唯讀取得文章資料
 * （授權判斷要即時答案，不能走事件）。
 * </p>
 *
 * <p>
 * <b>維護守則</b>：本介面的實作必須維持「只依賴 repository」。若日後為它加上任何
 * service / facade 依賴，很可能會把上述循環依賴重新引回來（且單元測試抓不到，只有真正啟動才會現形）。
 * 一般跨模組需求請優先擴充 {@link ArticleFacade}。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface ArticleLookupFacade {

    /**
     * 依文章公開 UUID 查詢 article 元資料。
     *
     * <p>不限文章狀態，回傳含 {@code status} / {@code authorId} 的 {@link ArticleData}，
     * 由呼叫端自行依狀態與擁有權判斷授權。</p>
     *
     * @param articleUuid 文章公開 UUID；為 null 時回傳 {@link Optional#empty()}
     * @return 文章元資料 Optional；查無文章時為 empty
     */
    Optional<ArticleData> findByUuid(UUID articleUuid);

    /**
     * 查出某作者所有<b>保留文章</b>（依 {@code ArticleStatus#isRemovedWithAuthorAccount} 判斷：PUBLISHED／ARCHIVED）
     * 內文與封面中引用的檔案 UUID。
     *
     * <p>用途：作者刪帳清檔（DATA-02／DATA-07）時，凡在此集合內的檔案一律保留，不論其 {@code file_metadata.article_uuid}
     * 綁到哪裡——封面圖不會被 file 模組的內文綁定回填（{@code ArticleFileBindingService}，只掃內文）綁定，同一張圖同時用在草稿與已發布文章時
     * 也可能只綁到草稿那篇。引用格式為 {@code /api/v1/files/{uuid}/content}（內文與 {@code coverImageUrl} 皆掃描）。</p>
     *
     * @param authorUuid 作者公開 UUID；為 null 時回傳空集合
     * @return 被保留文章引用的檔案 UUID（去重、小寫正規化後解析）；無則為空集合
     */
    Set<UUID> findFileUuidsReferencedByRetainedArticles(UUID authorUuid);

    /**
     * 依文章公開 UUID 取得文章<b>目前最新</b>的 Markdown 內文。
     *
     * <p>用途：file 模組消費 {@code ArticleContentChangedEvent} 後，以最新內文解析所引用的檔案並回填綁定
     * （事件本身只帶 articleUuid，不帶內文；取最新值使事件亂序或重送時結果仍收斂到最新內文）。</p>
     *
     * @param articleUuid 文章公開 UUID；為 null 時回傳 {@link Optional#empty()}
     * @return 文章存在時為其內文（內文為 null 時以空字串表示）；文章不存在時為 empty
     */
    Optional<String> findContentByUuid(UUID articleUuid);

    /**
     * 查出某作者<b>所有仍存在的文章</b>（<b>不分狀態</b>：DRAFT、PENDING_REVIEW、REJECTED、PUBLISHED、ARCHIVED）
     * 內文與封面中引用的檔案 UUID。
     *
     * <p>與 {@link #findFileUuidsReferencedByRetainedArticles} 的差別在於去留語意：後者回答「作者刪帳後哪些檔案還要留」
     * （草稿隨帳號移除，所以不算）；本方法回答「刪除<b>單篇</b>文章後，哪些檔案仍被作者其餘文章使用」——其餘文章（含草稿）
     * 都還在，圖被它們引用就不能刪。用途：file 模組在文章刪除（事件或每日孤兒校正）後清理綁定該文章的檔案（DATA-07）。
     * 引用格式與掃描方式同 {@link #findFileUuidsReferencedByRetainedArticles}。</p>
     *
     * @param authorUuid 作者公開 UUID；為 null 時回傳空集合
     * @return 作者名下仍存在的任一文章引用的檔案 UUID（去重）；無則為空集合
     */
    Set<UUID> findFileUuidsReferencedByAuthorArticles(UUID authorUuid);

    /**
     * 回傳其中仍存在於文章表的 UUID（<b>不分狀態</b>，僅被硬刪者不會出現）。
     *
     * <p>集合述詞，供 file 模組的每日孤兒校正使用（語意等同 {@code ArticleFacade#filterExistingUuids}；
     * file 不能依賴那顆胖 Bean，見本介面說明）。實作端自行去重並切批，caller 可一次傳入任意數量。</p>
     *
     * @param uuids 待查證的文章公開 UUID；null 或空集合回傳空集合；null 元素會被忽略
     * @return 仍存在的 UUID 子集
     */
    Set<UUID> filterExistingUuids(Collection<UUID> uuids);

    /**
     * 以 keyset 分頁取得「自 {@code since} 起有更新過」的文章公開 UUID，<b>不分文章狀態</b>（草稿、待審、已發布、退回、封存皆含）。
     *
     * <p>用途：file 模組的每日綁定校正（B1）——重新解析近期更新過的文章內文並回填「檔案 → 文章」綁定。
     * 刻意不限 PUBLISHED：草稿內文引用的圖片若停在未綁定，後續的未綁定檔清理（D6）會把它當成無主檔刪掉。
     * 「有更新」以 {@code articles.updated_at >= since} 判定。</p>
     *
     * <p>排序與游標比較都由資料庫決定（PostgreSQL uuid 以位元組序比較），呼叫端以上一頁最後一筆當
     * {@code afterUuidExclusive} 取下一頁，不可在 Java 端排序後當游標。回傳筆數少於 {@code limit} 即為最後一頁。
     * 傳輸量與 {@code limit}（頁大小）成正比，不隨資料量成長，故不需 {@code BatchedQuery} 切批。</p>
     *
     * @param since              更新時間下界（含）；不可為 null
     * @param afterUuidExclusive 游標（不含）；null 表示從頭
     * @param limit              本頁筆數上限；小於等於 0 回傳空清單
     * @return 升冪（由資料庫排序）的文章 UUID；無則為空清單
     * @throws IllegalArgumentException {@code since} 為 null（不可退化成「全部文章」）
     */
    List<UUID> findUuidsUpdatedSinceAnyStatus(LocalDateTime since, UUID afterUuidExclusive, int limit);
}
