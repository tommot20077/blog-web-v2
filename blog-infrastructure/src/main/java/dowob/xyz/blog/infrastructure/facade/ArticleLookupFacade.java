package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;

import java.util.Optional;
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
 * article 模組與 file 模組互相需要對方的資料，形成 Spring 建構子循環依賴：
 * </p>
 * <pre>
 * articleServiceImpl → articleCommandSubService → articleFileBinder
 *   → fileFacadeImpl → fileServiceImpl → articleFacadeImpl → articleServiceImpl
 * </pre>
 * <p>
 * （另有一條較短的同源環：{@code articleFacadeImpl → articleFileBinder → fileFacadeImpl
 * → fileServiceImpl → articleFacadeImpl}。）
 * </p>
 * <p>
 * 兩條環的共同「回邊」都是 <b>fileServiceImpl → articleFacadeImpl</b>：
 * {@code ArticleFacadeImpl} 是一顆「胖」Bean，其依賴閉包含 {@code ArticleService} 與
 * {@code ArticleFileBinder}，而後者又指回 {@code FileFacade}。file 模組只需要
 * 「這篇文章的作者是誰 / 目前狀態」兩項唯讀資料，卻因此被迫依賴整個 article 寫入路徑。
 * </p>
 * <p>
 * 因此比照 {@code SeriesFacadeImpl}「重構依賴鏈使環不成立」的既有慣例（本專案零個
 * {@code @Lazy}、也未開啟 {@code spring.main.allow-circular-references}），
 * 把 file 模組所需的最小查詢切出成本介面，由依賴閉包乾淨的 Bean 實作，環自然消失。
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
}
