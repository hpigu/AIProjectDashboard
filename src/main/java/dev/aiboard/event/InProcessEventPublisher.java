package dev.aiboard.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class InProcessEventPublisher implements BoardEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public InProcessEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(BoardEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
