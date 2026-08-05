package dev.aiboard.web;

import dev.aiboard.event.BoardEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 持有目前所有 {@code /api/events} 的 SSE 連線並廣播看板事件。
 *
 * <h2>為什麼廣播不能同步做</h2>
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 預設是<b>同步</b>的：它跑在
 * 送出 commit 的那條執行緒上，而那條執行緒正是 agent 的 MCP tool call。若在那裡
 * 逐一對每個 emitter 呼叫 {@code send()}，任何一個讀取太慢的瀏覽器都會讓 socket
 * 寫入阻塞，agent 的 {@code create_tasks} 就跟著卡住——一個開著分頁沒在看的
 * 瀏覽器，可以拖慢真正在做事的 agent。這個耦合沒有任何好處。
 *
 * <p>因此改成：commit 執行緒只負責「把事件放進每個客戶端的佇列」（不做 I/O），
 * 實際寫入交給背景執行緒池。
 *
 * <h2>慢客戶端的處理：踢掉，而不是默默丟事件</h2>
 * 每個客戶端有自己的有界佇列。佇列滿代表它已經跟不上，此時<b>結束該連線</b>而不是
 * 丟棄事件——前端在 SSE 斷線重連後本來就會整批重抓（見 {@code app.js} 的
 * {@code connectSse}），踢掉它反而讓它自我修復到正確狀態；默默丟事件則會讓畫面
 * 停在一個沒人知道是錯的狀態。
 *
 * <p>每個客戶端同時間只會有一個抽取任務在跑（{@code draining} 旗標），因此事件
 * 對單一連線而言仍保證有序，也不需要為每條連線各配一條執行緒。
 *
 * <h2>連線數上限</h2>
 * 每條連線都佔用一個 Tomcat async request 與一份佇列記憶體。這是單機看板，正常
 * 情況是個位數的分頁；設上限是為了讓「某個腳本狂開連線」這類異常以明確的 503
 * 收場，而不是慢慢把行程拖垮。
 *
 * <h2>關閉</h2>
 * 實作 {@link SmartLifecycle} 是必要的，不是加分項：SSE emitter 的 timeout 為 0
 * （永不逾時），對 Tomcat 而言是永遠不會結束的 async request。關閉時 Spring Boot
 * 的 graceful shutdown 會「等待進行中的請求完成」，只要有人開著看板頁面就會一直
 * 等下去，逾時後 JVM 仍不退出，留下一個握著 H2 {@code .mv.db} 鎖的行程，而
 * {@code bin/board stop} 早已回報「已停止」。{@link #getPhase()} 回傳
 * {@link Integer#MAX_VALUE}：Spring 依 phase 遞減順序停止 bean，而
 * {@code webServerGracefulShutdown} 的 phase 是 {@code Integer.MAX_VALUE - 1024}，
 * 必須比它大才會「先」被停掉。
 */
@Component
public class SseEmitterRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    /**
     * 背景寫入的執行緒數。刻意小：這是單機看板，同時連線是個位數，而每條執行緒
     * 都可能卡在慢客戶端的 socket 寫入上。多開幾條只是讓更多執行緒一起卡住。
     */
    private static final int DISPATCH_THREADS = 2;

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final int maxConnections;
    private final int clientQueueCapacity;

    private volatile boolean running;
    private volatile ExecutorService dispatcher;

    public SseEmitterRegistry(
            @Value("${board.sse.max-connections:32}") int maxConnections,
            @Value("${board.sse.client-queue-capacity:128}") int clientQueueCapacity) {
        this.maxConnections = maxConnections;
        this.clientQueueCapacity = clientQueueCapacity;
    }

    public int connectionCount() {
        return clients.size();
    }

    int maxConnections() {
        return maxConnections;
    }

    /**
     * 註冊一條新連線。
     *
     * @throws SseConnectionLimitExceededException 已達連線上限
     */
    public SseEmitter register() {
        SseEmitter emitter = newEmitter();

        // 關閉流程已經開始時不再收新連線。瀏覽器的 EventSource 收到 complete()
        // 之後會自動重連，若此時連接器還來得及接受請求，就會有新的永不結束的
        // request 再次把關閉流程卡住。
        if (!running) {
            emitter.complete();
            return emitter;
        }

        if (clients.size() >= maxConnections) {
            log.warn("[sse] 已達連線上限 {}，拒絕新連線", maxConnections);
            throw new SseConnectionLimitExceededException(maxConnections);
        }

        Client client = new Client(emitter, clientQueueCapacity);
        clients.add(client);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> clients.remove(client));
        emitter.onError(e -> clients.remove(client));

        // 第一則 heartbeat 直接同步送出：這裡本來就在該請求自己的執行緒上，
        // 讓客戶端立刻知道連線可用比排進佇列更直接。
        try {
            emitter.send(SseEmitter.event().name("heartbeat").data(Map.of()));
        } catch (IOException | IllegalStateException e) {
            clients.remove(client);
        }
        return emitter;
    }

    /** 讓測試能換掉 emitter 以模擬慢客戶端；正式路徑永遠是永不逾時的 SseEmitter。 */
    SseEmitter newEmitter() {
        return new SseEmitter(0L);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBoardEvent(BoardEvent event) {
        broadcast(event.type(), event.payload());
    }

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    public void broadcastHeartbeat() {
        broadcast("heartbeat", Map.of());
    }

    /**
     * 把事件排進每個客戶端的佇列並喚醒抽取任務。這個方法<b>不做 I/O</b>，因此在
     * commit 執行緒（也就是 agent 的 tool call）上呼叫是安全的。
     */
    void broadcast(String eventName, Object data) {
        ExecutorService executor = this.dispatcher;
        if (executor == null) {
            return;
        }
        Event event = new Event(eventName, data);
        for (Client client : clients) {
            if (!client.offer(event)) {
                // 佇列已滿＝這個客戶端跟不上。結束它的連線，讓前端重連後整批重抓，
                // 而不是繼續累積或默默丟事件。
                log.warn("[sse] 客戶端佇列已滿（容量 {}），關閉該連線讓它重連後重新同步", clientQueueCapacity);
                dropClient(client);
                continue;
            }
            scheduleDrain(client, executor);
        }
    }

    private void scheduleDrain(Client client, ExecutorService executor) {
        // 同一個客戶端同時間只允許一個抽取任務，藉此保證送達順序，也避免
        // 「連線數 × 事件數」的任務爆量。
        if (!client.draining.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> drain(client));
        } catch (RuntimeException ex) {
            // 執行緒池已關閉等情況：解開旗標，不讓客戶端永久停在 draining 狀態。
            client.draining.set(false);
        }
    }

    private void drain(Client client) {
        try {
            Event event;
            while ((event = client.queue.poll()) != null) {
                try {
                    client.emitter.send(SseEmitter.event().name(event.name()).data(event.data()));
                } catch (IOException | IllegalStateException ex) {
                    // 連線已斷（關閉瀏覽器分頁是最常見的情況），不是錯誤。
                    dropClient(client);
                    return;
                }
            }
        } finally {
            client.draining.set(false);
        }
        // 解開旗標與新事件入列之間有極短的競態：若剛好有事件在這段期間進來而沒人
        // 排程，這裡補一次。
        if (!client.queue.isEmpty()) {
            ExecutorService executor = this.dispatcher;
            if (executor != null) {
                scheduleDrain(client, executor);
            }
        }
    }

    private void dropClient(Client client) {
        clients.remove(client);
        client.queue.clear();
        try {
            client.emitter.complete();
        } catch (RuntimeException ignored) {
            // 已經斷掉的連線再 complete 一次不是問題。
        }
    }

    @Override
    public void start() {
        dispatcher = Executors.newFixedThreadPool(DISPATCH_THREADS, new DispatcherThreadFactory());
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        int closed = 0;
        for (Client client : clients) {
            try {
                client.emitter.complete();
                closed++;
            } catch (RuntimeException ignored) {
                // 個別連線可能已經斷了；關閉流程不該因為其中一條而中止。
            }
        }
        clients.clear();

        ExecutorService executor = this.dispatcher;
        this.dispatcher = null;
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        if (closed > 0) {
            log.info("[sse] 關閉 {} 條事件連線，讓 graceful shutdown 得以完成", closed);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private record Event(String name, Object data) {
    }

    private static final class Client {
        private final SseEmitter emitter;
        private final Queue<Event> queue;
        private final AtomicBoolean draining = new AtomicBoolean(false);

        private Client(SseEmitter emitter, int queueCapacity) {
            this.emitter = emitter;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
        }

        private boolean offer(Event event) {
            return queue.offer(event);
        }
    }

    private static final class DispatcherThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "board-sse-dispatch-" + counter.getAndIncrement());
            // daemon：這些執行緒絕不能成為 JVM 無法退出的理由——那正是這個類別
            // 另一半註解在講的問題。
            thread.setDaemon(true);
            return thread;
        }
    }
}
