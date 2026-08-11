package dev.aiboard.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 這裡測的不是「事件有沒有廣播出去」，而是關閉行為——那才是實際咬到人的地方。
 *
 * <p>症狀：瀏覽器開著看板時執行 {@code bin/board stop}，腳本回報「已停止」，但 JVM
 * 其實還活著並持續持有 H2 的 {@code .mv.db} 鎖，下一次 start 直接 MVStoreException。
 * 原因是 SSE emitter 的 timeout 設為 0（永不逾時），對 Tomcat 而言是永遠不會結束的
 * async request，Spring Boot 的 graceful shutdown 會一直等它。
 *
 * <p>已在真實環境重現並確認因果：關閉瀏覽器分頁的 SSE 連線之後，殘留的 JVM 立刻
 * 退出；修好之後同樣情境下 graceful shutdown 從「30 秒逾時後仍卡住」變成 3 毫秒完成。
 *
 * <p>用 MockMvc 打真正的 {@code /api/events} 而不是直接 new 一個 SseEmitter：
 * {@code SseEmitter} 的 completion callback 是由 MVC 的 async 基礎設施觸發的，
 * 沒有綁到實際請求時呼叫 {@code complete()} 不會有任何可觀察的效果，那樣的測試
 * 會通過但什麼都沒驗到。
 *
 * <p>本測試會呼叫 {@link SseEmitterRegistry#stop()}，讓 registry 進入停止狀態，
 * 因此刻意使用專屬的 datasource URL 以取得獨立的 application context，不與其他
 * 測試共用快取的 context。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:sse-registry-shutdown-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=/tmp/ai-project-board-sse-registry-test.log"
})
@AutoConfigureMockMvc
class SseEmitterRegistryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SseEmitterRegistry registry;

    @Test
    void stopsBeforeTheWebServersGracefulShutdownBean() {
        // Spring 依 phase 遞減順序停止 bean。webServerGracefulShutdown 的 phase 是
        // Integer.MAX_VALUE - 1024；要在它「等待進行中的請求」之前先把 SSE 收掉，
        // 這個 registry 的 phase 就必須比它大。這個斷言是為了讓日後有人「順手」把
        // phase 改回預設值時測試會紅，而不是等到某次 stop 又留下殘留行程。
        assertThat(registry.getPhase()).isGreaterThan(Integer.MAX_VALUE - 1024);
    }

    @Test
    void stopCompletesOpenSseRequestsAndRefusesReconnectsSoShutdownCanFinish() throws Exception {
        MvcResult openStream = mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(registry.connectionCount()).isEqualTo(1);
        assertThat(registry.isRunning()).isTrue();

        registry.stop();

        assertThat(registry.connectionCount()).isZero();
        assertThat(registry.isRunning()).isFalse();

        // complete() 確實傳到了 MVC 的 async 基礎設施——這正是 graceful shutdown
        // 在等的事。SseEmitter 是透過 DeferredResult 收尾的，因此觀察點是「async
        // 結果已就緒、dispatch 走得下去」；若 emitter 沒有被 complete，這一步會
        // 因為沒有 concurrent result 而失敗。
        mockMvc.perform(asyncDispatch(openStream)).andExpect(status().isOk());

        // 瀏覽器的 EventSource 收到 complete() 之後會自動重連。若此時還收得下新連線，
        // 就會有新的永不結束的 request 再次把關閉流程卡住。
        mockMvc.perform(get("/api/events")).andReturn();
        assertThat(registry.connectionCount()).isZero();
    }
}
