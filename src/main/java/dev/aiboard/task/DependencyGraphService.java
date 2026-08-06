package dev.aiboard.task;

import dev.aiboard.project.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only projection of a project's planned task dependencies. */
@Service
public class DependencyGraphService {

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository dependencyRepository;
    private final ProjectService projectService;

    public DependencyGraphService(TaskRepository taskRepository,
                                  TaskDependencyRepository dependencyRepository,
                                  ProjectService projectService) {
        this.taskRepository = taskRepository;
        this.dependencyRepository = dependencyRepository;
        this.projectService = projectService;
    }

    /**
     * Reads the complete graph with one task query and one project-scoped dependency query.
     * A task is claimable under precisely the same prerequisite rule used by claim_next_task:
     * it must be TODO and every direct prerequisite must be DONE.
     */
    @Transactional(readOnly = true)
    public DependencyGraph getDependencyGraph(Long projectId) {
        projectService.assertExists(projectId);
        List<Task> tasks = taskRepository.findByProjectIdOrderBySortOrderAsc(projectId);
        List<TaskDependency> dependencies = dependencyRepository.findByProjectId(projectId);

        Map<Long, Task> tasksById = new HashMap<>();
        Map<Long, List<Long>> prerequisitesByTask = new HashMap<>();
        for (Task task : tasks) {
            tasksById.put(task.getId(), task);
        }
        for (TaskDependency dependency : dependencies) {
            prerequisitesByTask.computeIfAbsent(dependency.getTaskId(), ignored -> new ArrayList<>())
                    .add(dependency.getDependsOnTaskId());
        }

        assertAcyclic(prerequisitesByTask);

        List<DependencyNode> nodes = tasks.stream()
                .map(task -> new DependencyNode(task.getId(), task.getTitle(), task.getCategory(),
                        task.getStatus().name(), task.getAssignee(), isClaimable(task, prerequisitesByTask, tasksById)))
                .toList();
        List<DependencyEdge> edges = dependencies.stream()
                .map(dependency -> new DependencyEdge(dependency.getDependsOnTaskId(), dependency.getTaskId()))
                .toList();
        return new DependencyGraph(nodes, edges);
    }

    private static boolean isClaimable(Task task, Map<Long, List<Long>> prerequisitesByTask,
                                       Map<Long, Task> tasksById) {
        if (task.getStatus() != TaskStatus.TODO) {
            return false;
        }
        return prerequisitesByTask.getOrDefault(task.getId(), List.of()).stream()
                .map(tasksById::get)
                .allMatch(prerequisite -> prerequisite != null
                        && prerequisite.getStatus() == TaskStatus.DONE);
    }

    private static void assertAcyclic(Map<Long, List<Long>> prerequisitesByTask) {
        Map<Long, VisitState> states = new HashMap<>();
        List<Long> path = new ArrayList<>();
        for (Long taskId : prerequisitesByTask.keySet()) {
            if (states.getOrDefault(taskId, VisitState.UNVISITED) == VisitState.UNVISITED) {
                visit(taskId, prerequisitesByTask, states, path);
            }
        }
    }

    private static void visit(Long taskId, Map<Long, List<Long>> prerequisitesByTask,
                              Map<Long, VisitState> states, List<Long> path) {
        VisitState state = states.getOrDefault(taskId, VisitState.UNVISITED);
        if (state == VisitState.VISITING) {
            int cycleStart = path.indexOf(taskId);
            List<Long> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
            cycle.add(taskId);
            throw new DependencyCycleException("偵測到任務相依循環：" + cycle.stream()
                    .map(id -> "#" + id)
                    .reduce((left, right) -> left + " -> " + right)
                    .orElse("未知循環"));
        }
        if (state == VisitState.VISITED) {
            return;
        }

        states.put(taskId, VisitState.VISITING);
        path.add(taskId);
        for (Long prerequisiteId : prerequisitesByTask.getOrDefault(taskId, List.of())) {
            visit(prerequisiteId, prerequisitesByTask, states, path);
        }
        path.removeLast();
        states.put(taskId, VisitState.VISITED);
    }

    private enum VisitState {
        UNVISITED,
        VISITING,
        VISITED
    }

    public record DependencyGraph(List<DependencyNode> nodes, List<DependencyEdge> edges) {
    }

    public record DependencyNode(Long id, String title, String category, String status,
                                 String assignee, boolean claimable) {
    }

    /** Edge direction is prerequisiteTaskId -> taskId. */
    public record DependencyEdge(Long prerequisiteTaskId, Long taskId) {
    }
}
