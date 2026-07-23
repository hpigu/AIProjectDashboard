package dev.aiboard.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class EventStreamController {

    private final SseEmitterRegistry registry;

    public EventStreamController(SseEmitterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/events")
    public SseEmitter streamEvents() {
        return registry.register();
    }
}
