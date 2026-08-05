package dev.aiboard.web;

import dev.aiboard.event.BoardEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 持有目前所有 {@code /api/events} 的 SSE 連線並廣播看板事件。
 *
 * <p>實作 {@link SmartLifecycle} 是必要的，不是加分項：SSE emitter 的 timeout 設為
 * 0（永不逾時），對 Tomcat 而言就是一個永遠不會結束的 async request。關閉時
 * Spring Boot 的 graceful shutdown 會「等待進行中的請求完成」，於是只要有人開著
 * 看板頁面，關閉流程就會卡在那裡直到逾時，逾時後 JVM 仍不會退出——留下一個握著
 * H2 {@code .mv.db} 鎖的行程，而 {@code bin/board stop} 早已回報「已停止」。
 * 下一次 start 只會撞上 MVStoreException。
 *
 * <p>因此在關閉時主動 {@link SseEmitter#complete()} 掉所有連線，讓那些 async
 * request 正常結束，graceful shutdown 才等得到。{@link #getPhase()} 回傳
 * {@link Integer#MAX_VALUE}：Spring 依 phase 遞減順序停止 bean，而
 * {@code webServerGracefulShutdown} 的 phase 是 {@code Integer.MAX_VALUE - 1024}，
 * 必須比它大才會「先」被停掉。
 */
@Component
public class SseEmitterRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private volatile boolean running;

    public int connectionCount() {
        return emitters.size();
    }

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);

        // 關閉流程已經開始時不再收新連線。瀏覽器的 EventSource 在收到 complete()
        // 之後會自動重連，若此時連接器還來得及接受請求，就會有新的永不結束的
        // request 再次把關閉流程卡住。
        if (!running) {
            emitter.complete();
            return emitter;
        }

        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("heartbeat").data(Map.of()));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBoardEvent(BoardEvent event) {
        broadcast(event.type(), event.payload());
    }

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    public void broadcastHeartbeat() {
        broadcast("heartbeat", Map.of());
    }

    private void broadcast(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        int closed = 0;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
                closed++;
            } catch (RuntimeException e) {
                // 個別連線可能已經斷了；關閉流程不該因為其中一條而中止。
                emitters.remove(emitter);
            }
        }
        emitters.clear();
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
}
