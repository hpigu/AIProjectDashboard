package dev.aiboard.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #145：Web UI zh-TW/en 國際化（#143）的伺服器端靜態回歸保護。
 *
 * <p>背景：本 repo 前端零建置、無 npm/Vite（見 AGENTS.md），無法使用 Jest/Vitest
 * 直接載入 {@code i18n.js} 執行斷言；本機也未內建 GraalVM JS/Nashorn 引擎可供
 * {@code javax.script} 使用。因此本測試不「執行」i18n.js，而是用純 Java 正規
 * 表示式解析檔案中兩份字典物件字面值的原始文字，重建為巢狀 {@code Map}
 * 後做結構性比對——這足以穩定捕捉「缺 key」「多 key」「插值 placeholder
 * 不一致」「空字串」這類最容易在雙語字典維護中回歸的問題，且不需要真的執行
 * JavaScript。</p>
 *
 * <p>需要真的執行 JS / 渲染瀏覽器才能驗證的行為（reload 後 localStorage 保留、
 * navigator.languages 偵測、手動切換語言、html lang 屬性即時更新、UI 對應
 * 元素的實際渲染文案）記錄在
 * {@code scripts/frontend-regression/README.md} 的「i18n 檢查」一節與
 * {@code docs/frontend-regression-checklist.md}，以手動執行的 CDP 腳本／人工
 * 清單覆蓋，見兩份文件。</p>
 */
class I18nDictionaryTest {

    private static final Path I18N_JS = Path.of("src/main/resources/static/i18n.js");
    private static final Path APP_JS = Path.of("src/main/resources/static/app.js");
    private static final Path INDEX_HTML = Path.of("src/main/resources/static/index.html");

    private static String source;
    private static Map<String, Object> zhTw;
    private static Map<String, Object> en;
    private static Set<String> supportedLocales;

    @BeforeAll
    static void loadAndParse() throws IOException {
        source = Files.readString(I18N_JS);
        Map<String, Map<String, Object>> dictionaries = parseDictionaries(source);
        assertThat(dictionaries)
                .as("i18n.js 應能解析出 dictionaries 物件，且至少含 zh-TW 與 en 兩個 locale")
                .containsKeys("zh-TW", "en");
        zhTw = dictionaries.get("zh-TW");
        en = dictionaries.get("en");
        supportedLocales = parseSupportedLocales(source);
    }

    // ---- 解析輔助 ----

    /**
     * 從 i18n.js 原始碼中截出 {@code const dictionaries = { ... };} 區塊，並手動
     * 追蹤大括號深度解析為 {@code Map<locale, Map<key, Object>>}（葉節點為
     * String value，中間節點為巢狀 Map）。刻意不用泛用 JSON 解析器，因為原始碼
     * 是 JS 物件字面值（key 可不加引號、用單引號字串），不是合法 JSON。
     */
    private static Map<String, Map<String, Object>> parseDictionaries(String js) {
        int start = js.indexOf("const dictionaries = {");
        assertThat(start).as("找不到 dictionaries 宣告").isGreaterThanOrEqualTo(0);
        int braceStart = js.indexOf('{', start);
        int end = matchBrace(js, braceStart);

        String body = js.substring(braceStart + 1, end);
        // body 現在是兩個 locale 區塊："'zh-TW': { ... }, 'en': { ... },"
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Matcher localeMatcher = Pattern.compile("'([\\w-]+)'\\s*:\\s*\\{").matcher(body);
        int cursor = 0;
        while (localeMatcher.find(cursor)) {
            String locale = localeMatcher.group(1);
            int objStart = localeMatcher.end() - 1;
            int objEnd = matchBrace(body, objStart);
            String objBody = body.substring(objStart + 1, objEnd);
            Map<String, Object> parsed = new LinkedHashMap<>();
            parseObjectBody(objBody, parsed);
            result.put(locale, parsed);
            cursor = objEnd + 1;
        }
        return result;
    }

    /**
     * 解析物件字面值內部（不含外層大括號）為 key -> value（String 葉節點或巢狀
     * Map）。逐行掃描：
     *  - {@code key: {}  => 進入巢狀物件，遞迴解析到對應的收合大括號}
     *  - {@code key: 'value',} => 字串葉節點
     * key 可能是裸識別字（app、title）或單引號字串（'zh-TW'、'en'，對應語言碼
     * 當 key 使用的情況，例如 lang 區塊裡的 'zh-TW': '中文'）。
     */
    private static void parseObjectBody(String body, Map<String, Object> out) {
        Pattern entry = Pattern.compile(
                "(?:'([^']*)'|([A-Za-z_][A-Za-z0-9_]*))\\s*:\\s*(\\{|'((?:[^'\\\\]|\\\\.)*)')");
        Matcher m = entry.matcher(body);
        while (m.find()) {
            String key = m.group(1) != null ? m.group(1) : m.group(2);
            if (m.group(3).equals("{")) {
                int objStart = m.end(3) - 1;
                int objEnd = matchBrace(body, objStart);
                Map<String, Object> nested = new LinkedHashMap<>();
                parseObjectBody(body.substring(objStart + 1, objEnd), nested);
                out.put(key, nested);
                m.region(objEnd + 1, body.length());
            } else {
                out.put(key, unescape(m.group(4)));
            }
        }
    }

    private static String unescape(String raw) {
        return raw.replace("\\'", "'").replace("\\\\", "\\");
    }

    /** 從第 {@code openIndex} 個 {@code '{'} 開始，回傳與其配對的 {@code '}'} 的 index。 */
    private static int matchBrace(String s, int openIndex) {
        Deque<Integer> stack = new ArrayDeque<>();
        boolean inString = false;
        char quote = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // 跳過跳脫字元
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inString = true;
                quote = c;
            } else if (c == '{') {
                stack.push(i);
            } else if (c == '}') {
                stack.pop();
                if (stack.isEmpty()) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("未找到配對的大括號，起始於 index " + openIndex);
    }

    private static Set<String> parseSupportedLocales(String js) {
        Matcher m = Pattern.compile("SUPPORTED_LOCALES\\s*=\\s*\\[([^]]*)]").matcher(js);
        assertThat(m.find()).as("找不到 SUPPORTED_LOCALES 宣告").isTrue();
        Set<String> out = new LinkedHashSet<>();
        Matcher item = Pattern.compile("'([^']+)'").matcher(m.group(1));
        while (item.find()) {
            out.add(item.group(1));
        }
        return out;
    }

    /** 攤平巢狀 Map 為 dot-path -> value（僅葉節點,對應 {@code t(key)} 實際查找的 key）。 */
    private static Map<String, String> flatten(Map<String, Object> dict) {
        Map<String, String> flat = new LinkedHashMap<>();
        flattenInto("", dict, flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(String prefix, Map<String, Object> dict, Map<String, String> out) {
        for (Map.Entry<String, Object> e : dict.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map) {
                flattenInto(path, (Map<String, Object>) value, out);
            } else {
                out.put(path, (String) value);
            }
        }
    }

    private static Set<String> placeholders(String value) {
        Set<String> names = new TreeSet<>();
        Matcher m = Pattern.compile("\\{(\\w+)}").matcher(value);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    // ---- 測試 ----

    @Test
    void supportedLocales_isExactlyZhTwAndEn() {
        assertThat(supportedLocales).containsExactlyInAnyOrder("zh-TW", "en");
    }

    @Test
    void bothDictionaries_containAtLeastTheExpectedLeafKeyCount() {
        Map<String, String> zhFlat = flatten(zhTw);
        Map<String, String> enFlat = flatten(en);
        // #143/#145 的既有基準是 129 個 leaf key；用 >= 而非 == 避免未來新增合法
        // key 時測試變成假警報，真正的守門在下面的 parity 測試（兩本字典必須
        // 完全一致，不管總數是多少）。
        assertThat(zhFlat.size()).as("zh-TW leaf key 數量").isGreaterThanOrEqualTo(129);
        assertThat(enFlat.size()).as("en leaf key 數量").isGreaterThanOrEqualTo(129);
    }

    @Test
    void dictionaries_haveExactKeyParity_noMissingNoExtra() {
        Set<String> zhKeys = flatten(zhTw).keySet();
        Set<String> enKeys = flatten(en).keySet();

        Set<String> missingInEn = new TreeSet<>(zhKeys);
        missingInEn.removeAll(enKeys);
        Set<String> missingInZh = new TreeSet<>(enKeys);
        missingInZh.removeAll(zhKeys);

        assertThat(missingInEn).as("en 字典缺少的 key（zh-TW 有、en 沒有）").isEmpty();
        assertThat(missingInZh).as("zh-TW 字典缺少的 key（en 有、zh-TW 沒有）").isEmpty();
    }

    @Test
    void everyLeafValue_isNonBlankString() {
        Map<String, String> zhFlat = flatten(zhTw);
        Map<String, String> enFlat = flatten(en);

        zhFlat.forEach((key, value) ->
                assertThat(value).as("zh-TW[%s] 不得為空字串", key).isNotBlank());
        enFlat.forEach((key, value) ->
                assertThat(value).as("en[%s] 不得為空字串", key).isNotBlank());
    }

    @Test
    void interpolationPlaceholders_matchExactlyBetweenLocales() {
        Map<String, String> zhFlat = flatten(zhTw);
        Map<String, String> enFlat = flatten(en);

        for (String key : zhFlat.keySet()) {
            if (!enFlat.containsKey(key)) continue; // parity 測試已經回報，這裡不重複噴錯
            Set<String> zhPlaceholders = placeholders(zhFlat.get(key));
            Set<String> enPlaceholders = placeholders(enFlat.get(key));
            assertThat(enPlaceholders)
                    .as("key '%s' 的插值 placeholder 應與 zh-TW 一致：zh-TW=%s, en=%s",
                            key, zhPlaceholders, enPlaceholders)
                    .isEqualTo(zhPlaceholders);
        }
    }

    /**
     * en 字典的值理論上不應該還殘留中文（代表忘了翻譯、複製貼上沒改）。
     * 唯一已知例外是 {@code lang.zh-TW}：這個 key 的語意是「用哪種文字標示
     * 『切換到中文』這個選項」，不管目前是哪個 locale 都應該顯示「中文」二字
     * 本身（否則使用者看不懂語言切換按鈕在講什麼語言），因此兩本字典這個
     * key 的 value 都刻意是「中文」，不是遺漏未翻譯。
     */
    @Test
    void enDictionary_hasNoUntranslatedChineseText_exceptKnownLanguageLabelException() {
        Map<String, String> enFlat = flatten(en);
        Pattern cjk = Pattern.compile("[\\u4e00-\\u9fff]");

        Set<String> offenders = new TreeSet<>();
        enFlat.forEach((key, value) -> {
            if (key.equals("lang.zh-TW")) return; // 唯一允許的例外，見上方 Javadoc
            if (cjk.matcher(value).find()) {
                offenders.add(key + "=" + value);
            }
        });
        assertThat(offenders).as("en 字典不應殘留未翻譯的中文字串").isEmpty();
    }

    @Test
    void zhDictionary_langLabels_areHumanReadable() {
        Map<String, String> zhFlat = flatten(zhTw);
        assertThat(zhFlat.get("lang.zh-TW")).isEqualTo("中文");
        assertThat(zhFlat.get("lang.en")).isEqualTo("English");
    }

    @Test
    void detectLocale_fallsBackToDefaultLocale_forUnsupportedOrMissingInput() {
        // 對應 i18n.js 的 detectLocale()：SUPPORTED_LOCALES 之外一律回退到
        // DEFAULT_LOCALE。這裡靜態驗證原始碼中 DEFAULT_LOCALE 常數確實是
        // 'zh-TW'（實際的 fallback 邏輯需要在瀏覽器中執行才能端到端驗證，
        // 見 docs/frontend-regression-checklist.md 的「不支援 locale 的
        // fallback」一項與 scripts/frontend-regression 的 i18n 檢查）。
        Matcher m = Pattern.compile("DEFAULT_LOCALE\\s*=\\s*'([^']+)'").matcher(source);
        assertThat(m.find()).as("找不到 DEFAULT_LOCALE 常數").isTrue();
        assertThat(m.group(1)).isEqualTo("zh-TW");
        assertThat(supportedLocales).contains(m.group(1));
    }

    @Test
    void missingKeyFallback_returnsWarningMarkedRawKey() {
        // t(key) 找不到 key 時的 raw-key fallback 標記（`⚠key`）與 console.warn
        // 是這份 i18n 實作辨識「翻譯漏了」的關鍵機制（#145 驗收條件之一：
        // 「測試能找出缺 key、raw key 與錯誤 fallback」）。靜態驗證這段邏輯
        // 存在於原始碼中，避免未來重構時被誤刪或改成靜默吞掉的行為。
        assertThat(source).contains("return `⚠${key}`");
        assertThat(source).contains("console.warn(`[i18n] missing key: ${key}`)");
    }

    @Test
    void htmlLangMapping_coversBothSupportedLocales() {
        Matcher m = Pattern.compile("HTML_LANG\\s*=\\s*\\{([^}]*)}", Pattern.DOTALL).matcher(source);
        assertThat(m.find()).as("找不到 HTML_LANG 對照表").isTrue();
        String body = m.group(1);
        assertThat(body).contains("'zh-TW': 'zh-Hant-TW'");
        assertThat(body).contains("'en': 'en'");
    }

    @Test
    void indexHtml_hasNoLeftoverHardcodedChineseUiText() {
        // index.html 應完全由 t(...) 驅動文案，不應該有寫死在模板裡、繞過 i18n
        // 的中文字串（例如忘了替換成 {{ t('...') }} 的殘留文字）。做法：移除
        // <script>/<style> 區塊與所有屬性值（含 t(...) 呼叫本身），只檢查剩下
        // 的標籤外文字節點與屬性名稱是否含中文字元；此檢查允許 HTML 註解與
        // Vue 指令语法本身，因為它們不是使用者看得到的文案。
        assertThat(INDEX_HTML).exists();
        String html;
        try {
            html = Files.readString(INDEX_HTML);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // 拿掉所有屬性值（可能含 t('...') 呼叫或其他非顯示用字串）
        String withoutAttrs = html.replaceAll("=\"[^\"]*\"", "=\"\"");
        // 拿掉所有 {{ ... }} Vue 插值表達式（含 t(...) 呼叫本身的 key 字串）
        String withoutInterpolation = withoutAttrs.replaceAll("\\{\\{[^}]*}}", "");
        Pattern cjk = Pattern.compile("[\\u4e00-\\u9fff]");
        Matcher m = cjk.matcher(withoutInterpolation);
        boolean found = m.find();
        String context = found
                ? withoutInterpolation.substring(Math.max(0, m.start() - 20), Math.min(withoutInterpolation.length(), m.end() + 20))
                : "";
        assertThat(found)
                .as("index.html 不應含有繞過 t(...) 的硬編碼中文字串：找到 '%s' 附近文字", context)
                .isFalse();
    }

    @Test
    void appJs_exposesAllExpectedI18nBindings() throws IOException {
        String appJs = Files.readString(APP_JS);
        assertThat(appJs)
                .as("app.js 必須從 window.__i18n 解構出 t/setLocale/state/SUPPORTED_LOCALES")
                .contains("window.__i18n");
        assertThat(appJs).containsPattern("const \\{[^}]*\\bt\\b[^}]*}\\s*=\\s*window\\.__i18n");
        assertThat(appJs).contains("setLocale");
        assertThat(appJs).contains("SUPPORTED_LOCALES");
    }

    /**
     * 掃描 app.js 中所有 {@code t('...')} / {@code t("...")} 呼叫（含
     * {@code this.t(...)}）引用的 key，確認每一個都存在於 zh-TW 字典中
     * （字典本身的 parity 已由另一測試保證 en 也會有）。避免呼叫端打錯
     * key 名稱、或字典改了 key 名稱但呼叫端沒同步更新，這類錯誤在瀏覽器
     * 中只會表現成 console.warn + `⚠key` 畫面文字，容易被忽略。
     */
    @Test
    void appJs_everyStaticTCallReferencesAnExistingKey() throws IOException {
        String appJs = Files.readString(APP_JS);
        Map<String, String> zhFlat = flatten(zhTw);

        Matcher m = Pattern.compile("\\bt\\(\\s*'([\\w.-]+)'").matcher(appJs);
        Set<String> checked = new TreeSet<>();
        Set<String> unknown = new TreeSet<>();
        while (m.find()) {
            String key = m.group(1);
            checked.add(key);
            if (!zhFlat.containsKey(key)) {
                unknown.add(key);
            }
        }
        assertThat(checked).as("預期 app.js 至少有數十個靜態 t(...) 呼叫").hasSizeGreaterThan(20);
        assertThat(unknown).as("app.js 呼叫了字典中不存在的 key").isEmpty();
    }

    /**
     * 反向掃描：index.html 中的 {@code t('...')} 呼叫（作為 Vue template
     * 表達式的一部分）同樣必須全部指向存在的 key。動態組出的 key（例如語言
     * 切換按鈕的 {@code t('lang.' + code)}）不是單一字面值，改由下一個測試
     * 針對「每個 supportedLocales 都有對應 lang.<code> key」驗證涵蓋到的組合。
     */
    @Test
    void indexHtml_everyStaticTCallReferencesAnExistingKey() throws IOException {
        String html = Files.readString(INDEX_HTML);
        Map<String, String> zhFlat = flatten(zhTw);

        // 只比對字面值後面緊接著 ')' 或 ',' 的呼叫（純字面值 key），跳過
        // 'lang.' + code 這種字串串接（動態 key，非本測試比對範圍）。
        Matcher m = Pattern.compile("\\bt\\(\\s*'([\\w.-]+)'\\s*[),]").matcher(html);
        Set<String> checked = new TreeSet<>();
        Set<String> unknown = new TreeSet<>();
        while (m.find()) {
            String key = m.group(1);
            checked.add(key);
            if (!zhFlat.containsKey(key)) {
                unknown.add(key);
            }
        }
        assertThat(checked).as("預期 index.html 至少有數個靜態 t(...) 呼叫").isNotEmpty();
        assertThat(unknown).as("index.html 呼叫了字典中不存在的 key").isEmpty();
    }

    /**
     * index.html 的語言切換按鈕用 {@code t('lang.' + code)} 動態組出 key，
     * {@code code} 來自 {@code supportedLocales}（即 {@code SUPPORTED_LOCALES}）。
     * 靜態掃描抓不到字串串接的完整 key，這裡改為針對每個支援的 locale code
     * 直接驗證 {@code lang.<code>} 確實存在於字典中，涵蓋同一份風險。
     */
    @Test
    void indexHtml_dynamicLangSwitchKey_isCoveredForEverySupportedLocale() {
        assertThat(source_hasLangSwitchPattern()).as("index.html 應含 t('lang.' + code) 動態語言切換文案").isTrue();
        Map<String, String> zhFlat = flatten(zhTw);
        Map<String, String> enFlat = flatten(en);
        for (String code : supportedLocales) {
            String key = "lang." + code;
            assertThat(zhFlat).as("zh-TW 缺少語言切換按鈕用的 key: %s", key).containsKey(key);
            assertThat(enFlat).as("en 缺少語言切換按鈕用的 key: %s", key).containsKey(key);
        }
    }

    private static boolean source_hasLangSwitchPattern() {
        String html;
        try {
            html = Files.readString(INDEX_HTML);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return html.contains("t('lang.' + code)");
    }

    @Test
    void i18nJs_exposesRequiredWindowApi() {
        assertThat(source).contains("window.__i18n = {");
        assertThat(source).contains("t,");
        assertThat(source).contains("setLocale,");
        assertThat(source).contains("state,");
        assertThat(source).contains("SUPPORTED_LOCALES,");
        assertThat(source).contains("dictionaries,");
    }

    @Test
    void localeDetection_readsFromLocalStorageBoardLocaleKey_beforeNavigatorLanguage() {
        // #145 驗收條件：覆蓋「reload 後語言保留」。這裡靜態驗證 detectLocale()
        // 確實優先讀 localStorage 的 board.locale，且只有在沒有合法值時才會
        // 往下看 navigator.languages——這是「使用者手動切換過的語言在整頁
        // reload 後仍保留」得以成立的程式碼基礎。端到端行為（真的操作
        // localStorage 並 reload 瀏覽器）見手動/CDP 腳本覆蓋範圍。
        assertThat(source).contains("STORAGE_KEY = 'board.locale'");
        int fnStart = source.indexOf("function detectLocale()");
        assertThat(fnStart).as("找不到 detectLocale() 函式").isGreaterThan(0);
        // 只在函式本體內比對順序，避免檔案開頭的中文設計說明註解（同樣提到
        // navigator.languages）造成誤判。
        int storedIdx = source.indexOf("localStorage.getItem(STORAGE_KEY)", fnStart);
        int navigatorIdx = source.indexOf("navigator.languages", fnStart);
        assertThat(storedIdx).as("應讀取 localStorage").isGreaterThan(fnStart);
        assertThat(navigatorIdx).as("應讀取 navigator.languages").isGreaterThan(fnStart);
        assertThat(storedIdx).as("localStorage 偵測順序必須早於 navigator.languages")
                .isLessThan(navigatorIdx);
    }

    @Test
    void browserLanguageDetection_treatsOtherChineseVariantsAsZhTwFallback() {
        // zh-CN / zh-Hans 等非 zh-TW/zh-Hant 中文變體應 fallback 到 zh-TW（即
        // DEFAULT_LOCALE），不是「猜測」成 zh-TW 以外的東西。靜態驗證判斷式
        // 的結構存在；實際跨變體輸入的行為由手動清單覆蓋。
        assertThat(source).contains("lower.startsWith('zh')");
        assertThat(source).contains("lower.includes('tw') || lower.includes('hant')");
        assertThat(source).contains("return DEFAULT_LOCALE;");
    }
}
