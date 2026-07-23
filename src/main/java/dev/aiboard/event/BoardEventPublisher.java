package dev.aiboard.event;

public interface BoardEventPublisher {

    void publish(BoardEvent event);
}
