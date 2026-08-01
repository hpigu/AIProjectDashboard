package dev.aiboard.web;

import dev.aiboard.event.BoardEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SseEmitterRegistry {

    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public int connectionCount() {
        return emitters.size();
    }

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);
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
}
