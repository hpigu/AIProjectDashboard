package dev.aiboard.project;

import dev.aiboard.event.BoardEvent;
import dev.aiboard.event.BoardEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProjectCreationExecutor {

    private final ProjectRepository projectRepository;
    private final BoardEventPublisher eventPublisher;

    public ProjectCreationExecutor(ProjectRepository projectRepository,
                                   BoardEventPublisher eventPublisher) {
        this.projectRepository = projectRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Project create(String name, String description) {
        Project saved = projectRepository.saveAndFlush(new Project(name, description));
        eventPublisher.publish(BoardEvent.projectCreated(saved.getId(), saved.getName()));
        return saved;
    }
}
