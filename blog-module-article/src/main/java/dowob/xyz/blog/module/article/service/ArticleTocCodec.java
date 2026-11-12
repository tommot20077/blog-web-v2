package dowob.xyz.blog.module.article.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.module.article.model.dto.response.TocEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文章章節導覽（TOC）的 JSON 編解碼器。
 *
 * <p>{@code articles.toc} 以 JSON 字串持久化，寫入端（建立 / 更新 / 版本還原）序列化、
 * 讀取端（Response Mapper）反序列化。三條寫入路徑與一條讀取路徑若各自內嵌一份
 * try/catch，很容易讓「失敗時回退成什麼」漂移，故集中在本元件。</p>
 *
 * <h3>不變量</h3>
 * <ul>
 *   <li>{@link #serialize(List)} 恆回傳合法 JSON 陣列字串，永不回 null——Spring Data JDBC
 *       對未顯式設值的欄位會送出顯式 NULL 覆寫既有資料（V19 schema 已留下此教訓）。</li>
 *   <li>{@link #deserialize(String)} 恆回傳非 null 清單，且不拋例外——TOC 資料缺失或損毀
 *       不應讓文章本體讀不出來。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleTocCodec {

    /** 空 TOC 的標準表示，讀寫兩端共用同一個常數避免漂移 */
    public static final String EMPTY_TOC_JSON = "[]";

    /** JSON 序列化工具（Spring Boot 自動配置的實例） */
    private final ObjectMapper objectMapper;

    /**
     * 將 TOC 條目序列化為可持久化至 {@code articles.toc} 的 JSON 字串。
     *
     * @param toc 渲染器產出的章節條目清單，可為 null
     * @return JSON 陣列字串，恆非 null；null / 空清單 / 序列化失敗皆回傳 {@value #EMPTY_TOC_JSON}
     */
    public String serialize(List<TocEntry> toc) {
        if (toc == null || toc.isEmpty()) {
            return EMPTY_TOC_JSON;
        }
        try {
            return objectMapper.writeValueAsString(toc);
        } catch (JsonProcessingException e) {
            log.warn("TOC 序列化失敗，改存空陣列：{}", e.getMessage());
            return EMPTY_TOC_JSON;
        }
    }

    /**
     * 將 {@code articles.toc} 的原始值反序列化為結構化的章節條目清單。
     *
     * @param tocJson 資料庫欄位原始值，可為 null 或空白字串
     * @return 章節條目清單，恆非 null；null / 空白 / 解析失敗皆回傳空清單
     */
    public List<TocEntry> deserialize(String tocJson) {
        if (tocJson == null || tocJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tocJson, new TypeReference<List<TocEntry>>() {});
        } catch (JsonProcessingException e) {
            log.warn("TOC 反序列化失敗，改回空陣列：{}", e.getMessage());
            return List.of();
        }
    }
}
