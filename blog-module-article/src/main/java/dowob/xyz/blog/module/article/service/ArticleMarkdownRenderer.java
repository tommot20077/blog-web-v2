package dowob.xyz.blog.module.article.service;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.html.AttributeProvider;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory;
import com.vladsch.flexmark.html.renderer.AttributablePart;
import com.vladsch.flexmark.html.renderer.LinkResolverContext;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.vladsch.flexmark.util.data.MutableDataSet;
import dowob.xyz.blog.module.article.model.dto.response.TocEntry;
import org.apache.commons.lang3.StringUtils;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文章 Markdown 渲染器。
 *
 * <p>支援 CommonMark 完整語法（heading / image / code block / table / list 等），
 * 渲染後再以 OWASP HtmlSanitizer 白名單剝除 dangerous 元素。</p>
 *
 * <h3>白名單範圍</h3>
 * <ul>
 *   <li>結構：p、br、hr、blockquote、ul、ol、li</li>
 *   <li>標題：h1-h6（h1 通常給文章標題，markdown 內習慣 h2 起；不過全部允許）</li>
 *   <li>標題錨點：h2、h3 允許 {@code id}，但值必須匹配 {@code heading-<slug>} 格式</li>
 *   <li>強調：strong、em、code、del、s</li>
 *   <li>程式碼區塊：pre、code（含 class for shiki/highlight）</li>
 *   <li>連結：a（href 限 http/https；強制 nofollow noopener、target=_blank）</li>
 *   <li>圖片：img（src 限 http/https；alt、title 屬性允許）</li>
 *   <li>表格：table、thead、tbody、tr、th、td</li>
 * </ul>
 *
 * <p>禁用：script、iframe、object、embed、form、input、style、link 等危險元素，
 * 以及任何 on* 事件屬性與 javascript: URL。</p>
 *
 * <h3>章節導覽（TOC）與 heading id</h3>
 * <p>渲染時自 Markdown AST 走訪 h2/h3 標題，產生依文件順序排列的 {@link TocEntry} 清單，
 * 並透過 flexmark {@link AttributeProvider} 對對應的 {@code <h2>}/{@code <h3>} 注入
 * {@code id="heading-<slug>"} 錨點。id 於消毒前注入，再由 sanitizer 以
 * {@link #HEADING_ID_PATTERN} 驗證：<b>不符合 {@code heading-<slug>} 格式的 id 會被剝除；
 * 符合該格式者一律放行</b>，包含使用者於 raw HTML 內手動寫入、恰好仿冒成
 * {@code heading-} 命名空間格式的 id（例如刻意仿冒某個真實標題 slug 的
 * {@code <h2 id="heading-安裝步驟">}）。此機制將攻擊面收斂在 {@code heading-} 命名空間內，
 * 可防止任意字串的 DOM clobbering id，但<b>不保證</b> TOC 錨點一定指向渲染器產生的真實標題
 * ——完整的 TOC 錨點防護是 best-effort，非強保證；如需強化（例如偵測/拒絕仿冒 id）應視為
 * 獨立的 backlog 項目，非本機制涵蓋範圍。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Service
public class ArticleMarkdownRenderer {

    /**
     * heading id 前綴。所有渲染器產生的錨點 id 皆以此開頭。
     */
    private static final String HEADING_ID_PREFIX = "heading-";

    /**
     * slug 最大長度（以 code point 計），對應 {@link #HEADING_ID_PATTERN} 的 {@code {1,64}}。
     */
    private static final int MAX_SLUG_LENGTH = 64;

    /**
     * slug 為空時的退路值，避免產生不符合 pattern 的 {@code heading-} 空錨點。
     */
    private static final String FALLBACK_SLUG = "section";

    /**
     * 合法 heading id 的格式：{@code heading-} 前綴加上 1~64 個 Unicode 字母/數字/連字號。
     * 供 sanitizer 白名單放行渲染器產生的 id、剝除不符此格式的 id。
     *
     * <p>package-private（非 private）：讓同套件的單元測試可直接複用同一個 Pattern
     * 物件驗證「渲染器自產 id 必然符合此格式」的不變量，避免測試另外複製一份正規表示式
     * 造成兩處定義漂移。</p>
     */
    static final Pattern HEADING_ID_PATTERN = Pattern.compile("^heading-[\\p{L}\\p{N}-]{1,64}$");

    /**
     * flexmark 解析選項（於 render 時重建帶 AttributeProvider 的 HtmlRenderer 時共用）。
     */
    private final MutableDataSet options;

    /**
     * Markdown 解析器。
     */
    private final Parser parser;

    /**
     * OWASP HTML 消毒白名單工廠。
     */
    private final PolicyFactory sanitizer;

    /**
     * 建構渲染器，初始化解析選項、解析器與 OWASP 消毒白名單。
     */
    public ArticleMarkdownRenderer() {
        this.options = new MutableDataSet();
        // 讓 flexmark 輸出 raw HTML，由 OWASP 後續消毒
        this.options.set(HtmlRenderer.ESCAPE_HTML, false);
        this.options.set(HtmlRenderer.SUPPRESS_HTML, false);

        this.parser = Parser.builder(options).build();

        this.sanitizer = new HtmlPolicyBuilder()
                // 結構
                .allowElements("p", "br", "hr", "blockquote", "ul", "ol", "li")
                // 標題
                .allowElements("h1", "h2", "h3", "h4", "h5", "h6")
                // 標題錨點：僅 h2/h3 放行 id，且值必須為 heading-<slug> 格式
                .allowAttributes("id").matching(HEADING_ID_PATTERN).onElements("h2", "h3")
                // 強調
                .allowElements("strong", "em", "code", "del", "s")
                // 程式碼區塊
                .allowElements("pre", "code")
                .allowAttributes("class").onElements("code", "pre")
                // 連結
                .allowElements("a")
                .allowUrlProtocols("http", "https")
                .allowAttributes("href").onElements("a")
                .requireRelNofollowOnLinks()
                .requireRelsOnLinks("noopener")
                // 圖片
                .allowElements("img")
                .allowUrlProtocols("http", "https")
                .allowAttributes("src", "alt", "title").onElements("img")
                // 表格
                .allowElements("table", "thead", "tbody", "tr", "th", "td")
                .allowAttributes("colspan", "rowspan").onElements("th", "td")
                .toFactory();
    }

    /**
     * Markdown → 安全 HTML 與章節導覽（TOC）。
     *
     * <p>TOC 自 AST 的 h2/h3 標題節點依文件順序取得；HTML 中對應標題注入
     * {@code id="heading-<slug>"} 錨點，並經 sanitizer 消毒。TOC 與 HTML 的 id 一致。</p>
     *
     * @param markdown 原始 Markdown，可為 null
     * @return 渲染結果（HTML + TOC）；null 輸入回傳 null，空字串輸入回傳
     *         {@code RenderResult("", List.of())}
     */
    public RenderResult render(String markdown) {
        if (markdown == null) {
            return null;
        }
        if (markdown.isEmpty()) {
            return new RenderResult("", List.of());
        }

        Node doc = parser.parse(markdown);

        /** 走訪 AST 收集 h2/h3 標題，計算去重後的 id，同時建立 TOC 與「節點 → id」對照表 */
        List<TocEntry> toc = new ArrayList<>();
        Map<Node, String> headingIds = new IdentityHashMap<>();
        Map<String, Integer> idCounts = new HashMap<>();
        /* 已實際產出的 id：保證最終 id 全篇唯一（序號 id 可能撞上別的標題的天然 slug） */
        Set<String> usedIds = new HashSet<>();
        for (Node node : doc.getDescendants()) {
            if (!(node instanceof Heading heading)) {
                continue;
            }
            int level = heading.getLevel();
            if (level != 2 && level != 3) {
                continue;
            }
            String text = StringUtils.trimToEmpty(new TextCollectingVisitor().collectAndGetText(heading));
            String id = buildHeadingId(text, idCounts, usedIds);
            // 不變量守門：buildHeadingId 承諾回傳必然符合 HEADING_ID_PATTERN 的 id。
            // 這裡再次驗證是防禦深度——若此斷言曾經失敗，代表 buildHeadingId 本身有 bug
            // （id 未過 sanitizer 白名單而被剝除，但 toc() 仍回傳該 id，形成死錨點），
            // 應在測試中被抓到，而不是讓不一致的 TOC 悄悄流出去。
            if (!HEADING_ID_PATTERN.matcher(id).matches()) {
                throw new IllegalStateException(
                        "產生的 heading id 不符合 HEADING_ID_PATTERN，將導致 TOC 與 HTML id 不一致：" + id);
            }
            headingIds.put(heading, id);
            toc.add(new TocEntry(id, text, level));
        }

        /** 以帶 AttributeProvider 的 HtmlRenderer 於消毒前注入 heading id（依節點對照表） */
        HtmlRenderer htmlRenderer = HtmlRenderer.builder(options)
                .attributeProviderFactory(new IndependentAttributeProviderFactory() {
                    @Override
                    public AttributeProvider apply(LinkResolverContext context) {
                        return (node, part, attributes) -> {
                            if (part == AttributablePart.NODE) {
                                String id = headingIds.get(node);
                                if (id != null) {
                                    attributes.replaceValue("id", id);
                                }
                            }
                        };
                    }
                })
                .build();

        String html = htmlRenderer.render(doc);
        String sanitized = sanitizer.sanitize(html);
        // OWASP policy 對 target 屬性比較嚴格，後處理只對「沒有 target」的 <a> 補上 target="_blank"
        // negative lookahead 避免重複注入造成 target="_blank" target="_blank" 之類無效 HTML
        sanitized = sanitized.replaceAll("(?i)(<a\\b(?![^>]*\\btarget\\s*=)[^>]*?)>", "$1 target=\"_blank\">");
        return new RenderResult(sanitized, toc);
    }

    /**
     * 依標題文字建立去重後的 heading id。
     *
     * <p>id = {@code heading-} + Unicode slug；同文重複的 slug 附加序號去重
     * （例：{@code heading-安裝步驟}、{@code heading-安裝步驟-2}）。</p>
     *
     * <p><b>去重序號 headroom</b>：{@link #slugify(String)} 已將 slug 截斷至
     * {@value #MAX_SLUG_LENGTH} 個 code point 上限，這與 {@link #HEADING_ID_PATTERN}
     * 的 {@code {1,64}} 剛好吃滿。若直接在此 baseId 後面再接 {@code -<序號>}，兩個
     * ≥64 字的相同標題會讓第二個 id 超過上限而不符合 pattern（sanitizer 會剝除該 id，
     * 但 {@code toc()} 仍回傳含此 id 的條目，形成死錨點）。因此僅在需要附加序號時
     * （{@code count > 1}），才依序號的實際字元數（例如兩位數的 {@code -11} 佔 3 碼）
     * 動態讓出對應的 headroom，再次截斷 base slug，確保「slug 部份 + 序號」恆
     * {@code <= MAX_SLUG_LENGTH}。{@code count} 為 {@code int}，十進位最多 10 位數，
     * 故序號字串最長 11 碼（{@code -} + 10 位數），headroom 下限恆為
     * {@code MAX_SLUG_LENGTH - 11 = 53 > 0}，不會發生截斷後無字元可用的情形。</p>
     *
     * <p><b>最終 id 去重（非僅 baseId 計數）</b>：只對 baseId 計數不足以保證唯一，因為
     * 去重序號產生的 id 可能與另一個標題「天然」slug 相同——例如
     * {@code ## 安裝步驟} / {@code ## 安裝步驟 2} / {@code ## 安裝步驟}，第三個標題的
     * {@code heading-安裝步驟-2} 會撞上第二個標題的天然 slug。結果是 HTML 出現重複 id
     * （無效 HTML），且第二條 TOC 點下去會跳到第一條。因此改為對<b>實際產出的 id</b>
     * 記錄使用狀況，撞到就繼續遞增序號直到唯一。每次遞增都產生不同的後綴字串，
     * 且已用 id 集合有限，迴圈必然終止。</p>
     *
     * @param text     標題純文字
     * @param idCounts 記錄各基底 id 出現次數的可變對照表（跨呼叫累積同一篇文章的計數）
     * @param usedIds  已實際產出過的 id 集合（跨呼叫累積），用於保證最終 id 全篇唯一
     * @return 去重後、保證匹配 {@link #HEADING_ID_PATTERN} 且全篇唯一的 heading id
     */
    private String buildHeadingId(String text, Map<String, Integer> idCounts, Set<String> usedIds) {
        String slug = slugify(text);
        if (slug.isEmpty()) {
            slug = FALLBACK_SLUG;
        }
        String baseId = HEADING_ID_PREFIX + slug;

        int count = idCounts.merge(baseId, 1, Integer::sum);
        String candidate = (count == 1) ? baseId : buildNumberedId(slug, count);
        while (!usedIds.add(candidate)) {
            candidate = buildNumberedId(slug, idCounts.merge(baseId, 1, Integer::sum));
        }
        return candidate;
    }

    /**
     * 以「slug + 去重序號」組出 heading id，並依序號的實際字元數讓出對應 headroom。
     *
     * @param slug  已 slugify 的標題文字
     * @param count 去重序號（{@code >= 2}）
     * @return 長度恆 {@code <= HEADING_ID_PREFIX + MAX_SLUG_LENGTH} 的 heading id
     */
    private String buildNumberedId(String slug, int count) {
        String suffix = "-" + count;
        return HEADING_ID_PREFIX + truncateToCodePoints(slug, MAX_SLUG_LENGTH - suffix.length()) + suffix;
    }

    /**
     * 將標題文字轉為 Unicode slug。
     *
     * <p>規則：字母/數字保留並轉小寫；空白與其餘字元轉為連字號；避免連續連字號；
     * 去除首尾連字號；截斷至 {@value #MAX_SLUG_LENGTH} 個 code point。允許 Unicode
     * 以免中文標題退化為空字串。</p>
     *
     * @param text 標題純文字
     * @return slug（可能為空字串，表示標題不含任何字母/數字）
     */
    private String slugify(String text) {
        StringBuilder builder = new StringBuilder();
        // 初始視為連字號狀態，使開頭的非字母/數字字元不產生前導連字號
        boolean lastHyphen = true;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(Character.toLowerCase(codePoint));
                lastHyphen = false;
            } else if (!lastHyphen) {
                builder.append('-');
                lastHyphen = true;
            }
        }

        String slug = stripTrailingHyphens(builder.toString());
        return truncateToCodePoints(slug, MAX_SLUG_LENGTH);
    }

    /**
     * 以 code point 為單位將字串截斷至指定上限，避免切斷 surrogate pair，
     * 並在截斷後再次去除尾端連字號（截斷點可能剛好落在連字號上）。
     *
     * <p>由 {@link #slugify(String)}（截斷至 {@value #MAX_SLUG_LENGTH}）與
     * {@link #buildHeadingId(String, Map)}（去重序號 headroom，截斷至更短的上限）
     * 共用，確保兩處的截斷語意一致。</p>
     *
     * @param slug          待截斷的 slug（已不含前導連字號）
     * @param maxCodePoints 允許的最大 code point 數
     * @return 截斷（必要時）且去除尾端連字號後的字串
     */
    private static String truncateToCodePoints(String slug, int maxCodePoints) {
        if (slug.codePointCount(0, slug.length()) <= maxCodePoints) {
            return slug;
        }
        int cut = slug.offsetByCodePoints(0, maxCodePoints);
        return stripTrailingHyphens(slug.substring(0, cut));
    }

    /**
     * 去除字串尾端連續的連字號。
     *
     * @param s 原始字串
     * @return 去除尾端連字號後的字串
     */
    private static String stripTrailingHyphens(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * Markdown → 純文字（用於摘要提取）。不需 sanitize（純文字無 HTML 風險）。
     *
     * @param markdown 原始 Markdown，可為 null
     * @return 純文字；null 輸入回傳 null
     */
    public String toPlainText(String markdown) {
        if (markdown == null) {
            return null;
        }
        Node doc = parser.parse(markdown);
        TextCollectingVisitor visitor = new TextCollectingVisitor();
        return visitor.collectAndGetText(doc);
    }
}
