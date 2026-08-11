package dev.aiboard.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 廣播的背壓行為（#12）。
 *
 * <p>問題的本質是一條不該存在的耦合：{@code @TransactionalEventListener(AFTER_COMMIT)}
 * 預設同步執行，跑在送出 commit 的那條執行緒上——而那正是 agent 的 MCP tool call。
 * 廣播若在那裡逐一 {@code send()}，任何一個讀取太慢的瀏覽器都會讓 socket 寫入阻塞，
 * agent 的 {@code create_tasks} 就跟著卡住。一個沒人在看的分頁可以拖慢真正在做事
 * 的 agent。
 *
 * <p>因此這裡的測試都圍繞同一個問題：<b>慢客戶端不得影響呼叫端</b>。
 * 用一個會卡住的假 emitter 取代真的 SseEmitter（{@link SseEmitterRegistry#newEmitter()}
 * 就是為此留的縫），因為真實的「socket 緩衝寫滿」在單元測試裡沒辦法穩定重現。
 */
class SseBackpressureTest {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private SseEmitterRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null && registry.isRunning()) {
            registry.stop();
        }
    }

    /** 每次 register() 都取用預先排好的 emitter，讓測試能決定哪一條連線是慢的。 */
    private SseEmitterRegistry registryWith(int maxConnections, int queueCapacity,
                                            List<SseEmitter> queuedEmitters) {
        SseEmitterRegistry registry = new SseEmitterRegistry(maxConnections, queueCapacity) {
            private int index;

            @Override
            SseEmitter newEmitter() {
                if (index < queuedEmitters.size()) {
                    return queuedEmitters.get(index++);
                }
                return new RecordingEmitter();
            }
        };
        registry.start();
        this.registry = registry;
        return registry;
    }

    @BeforeEach
    void resetEmitters() {
        emitters.clear();
    }

    @Test
    void broadcastReturnsImmediatelyEvenWhenAClientIsStuckMidWrite() throws Exception {
        BlockingEmitter stuck = new BlockingEmitter();
        RecordingEmitter healthy = new RecordingEmitter();
        SseEmitterRegistry registry = registryWith(32, 128, List.of(stuck, healthy));

        registry.register();   // 慢客戶端
        registry.register();   // 正常客戶端

        // 第一則事件會讓 dispatch 執行緒卡在慢客戶端的 send() 裡。
        registry.broadcast("task.status_changed", Map.of("taskId", 1));
        assertThat(stuck.entered.await(2, TimeUnit.SECONDS))
                .as("慢客戶端應該已經進入 send() 並卡住")
                .isTrue();

        // 關鍵斷言：呼叫端（正式路徑上就是 agent 的 tool call 執行緒）不得被拖住。
        long startedAt = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            registry.broadcast("task.status_changed", Map.of("taskId", i));
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs)
                .as("廣播只入列不做 I/O，即使有客戶端卡在 send() 也必須立刻返回（實際耗時 %d ms）", elapsedMs)
                .isLessThan(1_000);

        // 正常客戶端不受慢客戶端影響，仍然收得到事件。
        assertThat(healthy.awaitAtLeast(2, 2, TimeUnit.SECONDS))
                .as("健康的客戶端不應被另一條連線的阻塞餓死")
                .isTrue();

        stuck.release();
    }

    @Test
    void clientThatFallsTooFarBehindIsDisconnectedInsteadOfBufferingForever() throws Exception {
        BlockingEmitter stuck = new BlockingEmitter();
        SseEmitterRegistry registry = registryWith(32, 4, List.of(stuck));

        registry.register();
        assertThat(registry.connectionCount()).isEqualTo(1);

        registry.broadcast("task.status_changed", Map.of("seq", -1));
        assertThat(stuck.entered.await(2, TimeUnit.SECONDS)).isTrue();

        // 佇列容量 4；持續灌事件直到滿為止。
        for (int i = 0; i < 50; i++) {
            registry.broadcast("task.status_changed", Map.of("seq", i));
        }

        // 踢掉而不是無限累積：前端重連後會整批重抓，比默默丟事件、讓畫面停在
        // 一個沒人知道是錯的狀態要好。
        assertThat(registry.connectionCount())
                .as("跟不上的客戶端應該被斷線，而不是繼續緩衝")
                .isZero();

        stuck.release();
    }

    @Test
    void connectionsBeyondTheLimitAreRejectedWithAClearError() {
        SseEmitterRegistry registry = registryWith(2, 128, List.of());

        registry.register();
        registry.register();
        assertThat(registry.connectionCount()).isEqualTo(2);

        assertThatThrownBy(registry::register)
                .isInstanceOf(SseConnectionLimitExceededException.class)
                .hasMessageContaining("2");

        assertThat(registry.connectionCount())
                .as("被拒絕的連線不得留在名單裡")
                .isEqualTo(2);
    }

    @Test
    void eventsReachAClientInTheOrderTheyWerePublished() throws Exception {
        RecordingEmitter client = new RecordingEmitter();
        SseEmitterRegistry registry = registryWith(32, 128, List.of(client));
        registry.register();

        for (int i = 0; i < 20; i++) {
            registry.broadcast("task.status_changed", Map.of("seq", i));
        }

        assertThat(client.awaitAtLeast(21, 5, TimeUnit.SECONDS))
                .as("應收到 1 則註冊時的 heartbeat 加 20 則事件")
                .isTrue();

        // 每個客戶端同時間只有一個抽取任務（draining 旗標），順序因此必須保持。
        List<Object> received = client.payloads.stream().skip(1).toList();
        for (int i = 0; i < 20; i++) {
            assertThat(received.get(i)).isEqualTo(Map.of("seq", i));
        }
    }

    @Test
    void stopShutsDownDispatchThreadsSoTheyCannotKeepTheJvmAlive() throws Exception {
        registryWith(32, 128, List.of()).register();
        registry.broadcast("task.status_changed", Map.of("x", 1));

        registry.stop();

        assertThat(registry.isRunning()).isFalse();
        assertThat(registry.connectionCount()).isZero();

        // 執行緒都是 daemon，且 stop() 會 shutdownNow：不得有任何 dispatch 執行緒
        // 存活到能阻止 JVM 退出。
        boolean anyAlive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().startsWith("board-sse-dispatch-") && t.isAlive() && !t.isDaemon());
        assertThat(anyAlive).isFalse();
    }

    // ------------------------------------------------------------------
    // 測試替身
    // ------------------------------------------------------------------

    /** 記錄收到的事件內容。 */
    private static class RecordingEmitter extends SseEmitter {
        private final List<Object> payloads = new CopyOnWriteArrayList<>();

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            payloads.add(extractData(builder));
        }

        boolean awaitAtLeast(int count, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (payloads.size() >= count) {
                    return true;
                }
                Thread.sleep(10);
            }
            return payloads.size() >= count;
        }
    }

    /** 模擬「socket 緩衝寫滿、send() 卡住」的客戶端。 */
    private static class BlockingEmitter extends SseEmitter {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        BlockingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            entered.countDown();
            try {
                released.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ex);
            }
        }

        void release() {
            released.countDown();
        }
    }

    /**
     * SseEventBuilder 沒有公開的取值方法，但它會把資料交給 DataWithMediaType；
     * 這裡只取出我們自己放進去的物件，用於斷言順序與內容。
     */
    private static Object extractData(SseEmitter.SseEventBuilder builder) {
        AtomicReference<Object> found = new AtomicReference<>();
        builder.build().forEach(part -> {
            Object data = part.getData();
            if (!(data instanceof String)) {
                found.compareAndSet(null, data);
            }
        });
        return found.get();
    }
}
