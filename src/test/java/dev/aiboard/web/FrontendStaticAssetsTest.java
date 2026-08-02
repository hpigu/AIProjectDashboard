package dev.aiboard.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 前端零建置架構的伺服器端回歸保護（#140）。
 *
 * 背景：#126 曾用瀏覽器自動化（Node/CDP，臨時 scratchpad 腳本，未進 repo）驗證了
 * 87 項前端行為，其中一部分本質上是「靜態資源是否真的能以同源相對路徑被實際取得」，
 * 這件事不需要真的開瀏覽器，MockMvc 打進真正的 Spring MVC / 靜態資源 handler
 * 即可驗證，且可長期留在 CI 內、不需新增任何依賴。
 *
 * 本測試與既有的 {@code OperationalSafetyConfigurationTest#uiIsSelfContainedAndCspCannotFetchExternalDependencies}
 * 互補：該測試只讀檔案內容字串（不含外部 URL、vendor 檔案存在），本測試進一步驗證
 * index.html／app.js 中引用的每一個資源路徑，透過真正的 HTTP GET 都能成功取得
 * 且 content-type 正確——避免「檔案存在但路徑對不上、被 Spring 資源設定擋掉」這種
 * 只有實際發請求才會發現的回歸。
 *
 * 未涵蓋（仍需瀏覽器才能驗證，見 docs/frontend-regression-checklist.md）：
 * RWD breakpoint 版面量測、鍵盤焦點歸還、URL 篩選狀態往返、CSP violation 事件、
 * console error、SSE 斷線重連後的畫面狀態、API 呼叫次數計數。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:frontend-static-assets-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-frontend-static-assets-test.log"
})
@AutoConfigureMockMvc
class FrontendStaticAssetsTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "/index.html",
            "/app.js",
            "/fonts.css",
            "/tokens.css",
            "/layout.css",
            "/vendor/vue/vue.global.prod.js",
            "/vendor/fonts/archivo-700.woff2",
            "/vendor/fonts/archivo-800.woff2",
            "/vendor/fonts/ibm-plex-sans-400.woff2",
            "/vendor/fonts/ibm-plex-sans-500.woff2",
            "/vendor/fonts/ibm-plex-mono-400.woff2",
            "/vendor/fonts/ibm-plex-mono-500.woff2",
    })
    void everyAssetReferencedByIndexHtmlOrFontsCss_isServedSuccessfullyFromSameOrigin(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test
    void vueVendorScript_isServedAsJavaScript() throws Exception {
        mockMvc.perform(get("/vendor/vue/vue.global.prod.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("javascript")));
    }

    @Test
    void fontFiles_areServedWithWoff2ContentType() throws Exception {
        mockMvc.perform(get("/vendor/fonts/archivo-700.woff2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.containsString("font/woff2"),
                        org.hamcrest.Matchers.containsString("application/octet-stream"))));
    }

    /**
     * index.html 內每一個 href/src 屬性值都必須是以 "/" 開頭的相對路徑
     * （同源），不得指向 http(s):// 外部主機——這是 #109/#126 建立的零跨
     * origin 資源承諾，用來防止未來有人不小心又加回 CDN 連結。
     */
    @Test
    void indexHtml_everyAssetReference_isRelativeSameOriginPath() throws Exception {
        String index = Files.readString(Path.of("src/main/resources/static/index.html"));
        Pattern attr = Pattern.compile("(?:href|src)=\"([^\"]+)\"");
        Matcher matcher = attr.matcher(index);

        int found = 0;
        while (matcher.find()) {
            found++;
            String value = matcher.group(1);
            assertThat(value)
                    .as("index.html 資源引用必須是同源相對路徑: %s", value)
                    .startsWith("/")
                    .doesNotContainPattern("(?i)^https?://");
        }
        assertThat(found).as("預期 index.html 至少引用 5 個資源").isGreaterThanOrEqualTo(5);
    }

    /**
     * app.js 內所有 fetch(...) 呼叫的目標必須是以 "/api" 開頭的同源相對路徑，
     * EventSource 必須指向 "/api/events"。避免未來改動把某個呼叫寫成帶主機的
     * 絕對 URL，造成瀏覽器發出跨 origin 請求（#126 驗證過的「零跨 origin 請求」
     * 承諾在此以靜態掃描方式長期把關）。
     */
    @Test
    void appJs_allNetworkCalls_targetRelativeApiPaths() throws Exception {
        String appJs = Files.readString(Path.of("src/main/resources/static/app.js"));

        assertThat(appJs).contains("new EventSource('/api/events')");
        assertThat(appJs).doesNotContain("http://", "https://");

        Pattern fetchCall = Pattern.compile("fetchJson\\((?:'([^']+)'|`([^`]+)`)");
        Matcher matcher = fetchCall.matcher(appJs);
        int found = 0;
        while (matcher.find()) {
            String target = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            found++;
            assertThat(target)
                    .as("app.js 的 API 呼叫必須是同源相對路徑: %s", target)
                    .startsWith("/api");
        }
        assertThat(found).as("預期 app.js 至少有數個 fetchJson 呼叫").isGreaterThan(0);
    }
}
