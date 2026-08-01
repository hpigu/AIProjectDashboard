package dev.aiboard.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import dev.aiboard.common.BoardException;
import dev.aiboard.event.BoardEvent;
import dev.aiboard.event.BoardEventPublisher;
import dev.aiboard.project.ProjectService;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskLogRepository taskLogRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private BoardEventPublisher eventPublisher;

    @Mock
    private TaskDependencyRepository taskDependencyRepository;

    private TaskService taskService;

    private final ClaimTokenService claimTokenService = new ClaimTokenService();

    @BeforeEach
    void setUp() {
        taskService = new TaskService(taskRepository, taskLogRepository, taskDependencyRepository,
                projectService, eventPublisher, claimTokenService);
    }

    @Test
    void createTasks_batchCreatesWithContinuedSortOrderAndLogs() {
        Long projectId = 12L;
        doNothing().when(projectService).assertExistsForUpdate(projectId);
        when(taskRepository.findMaxSortOrder(projectId)).thenReturn(2);
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<TaskService.TaskInput> inputs = List.of(
                new TaskService.TaskInput("建立資料庫 schema", null, "BACKEND"),
                new TaskService.TaskInput("實作交易 CRUD API", "含分頁", "BACKEND"),
                new TaskService.TaskInput("不合法分類任務", null, "NOT_A_CATEGORY")
        );

        List<TaskService.TaskDto> results = taskService.createTasks(projectId, inputs);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).sortOrder()).isEqualTo(3);
        assertThat(results.get(1).sortOrder()).isEqualTo(4);
        assertThat(results.get(2).sortOrder()).isEqualTo(5);
        assertThat(results.get(2).category()).isEqualTo("OTHER");

        verify(taskRepository, times(3)).save(any(Task.class));
        verify(taskLogRepository, times(3)).save(any(TaskLog.class));

        ArgumentCaptor<TaskLog> logCaptor = ArgumentCaptor.forClass(TaskLog.class);
        verify(taskLogRepository, times(3)).save(logCaptor.capture());
        assertThat(logCaptor.getAllValues())
                .allSatisfy(log -> {
                    assertThat(log.getFromStatus()).isNull();
                    assertThat(log.getToStatus()).isEqualTo(TaskStatus.TODO.name());
                });
    }

    @Test
    void createTasks_whenProjectDoesNotExist_throwsAndDoesNotSaveAnything() {
        Long projectId = 999L;
        doThrow(new BoardException("找不到專案：#999")).when(projectService).assertExistsForUpdate(projectId);

        List<TaskService.TaskInput> inputs = List.of(new TaskService.TaskInput("任務", null, null));

        assertThatThrownBy(() -> taskService.createTasks(projectId, inputs))
                .isInstanceOf(BoardException.class);

        verify(taskRepository, times(0)).save(any(Task.class));
        verify(taskLogRepository, times(0)).save(any(TaskLog.class));
    }

    @Test
    void createTasks_whenInputsEmpty_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> taskService.createTasks(12L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTasks_whenTitleBlank_throwsIllegalArgumentException() {
        List<TaskService.TaskInput> inputs = List.of(new TaskService.TaskInput("  ", null, null));

        assertThatThrownBy(() -> taskService.createTasks(12L, inputs))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTasks_whenCategoryMissing_usesOther() {
        Long projectId = 12L;
        doNothing().when(projectService).assertExistsForUpdate(projectId);
        when(taskRepository.findMaxSortOrder(projectId)).thenReturn(-1);
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<TaskService.TaskDto> results = taskService.createTasks(projectId,
                List.of(new TaskService.TaskInput("待分類任務", null, null)));

        assertThat(results.getFirst().category()).isEqualTo("OTHER");
    }

    @Test
    void createTasks_whenMoreThanFifty_throwsIllegalArgumentException() {
        List<TaskService.TaskInput> inputs = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> new TaskService.TaskInput("任務 " + i, null, "OTHER"))
                .toList();

        assertThatThrownBy(() -> taskService.createTasks(12L, inputs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50");
    }

    @Test
    void createTasks_whenTitleExceedsThreeHundredCharacters_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> taskService.createTasks(12L,
                List.of(new TaskService.TaskInput("x".repeat(301), null, "OTHER"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("300");
    }

    @Test
    void listTasks_withStatusFilter_delegatesToRepositoryFilter() {
        Long projectId = 12L;
        doNothing().when(projectService).assertExists(projectId);
        Task task = new Task(projectId, "任務 A", null, "BACKEND", 0);
        when(taskRepository.findByProjectIdAndOptionalFilters(projectId, "TODO", null))
                .thenReturn(List.of(task));

        List<TaskService.TaskDto> results = taskService.listTasks(projectId, "todo");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("任務 A");
        verify(taskRepository).findByProjectIdAndOptionalFilters(projectId, "TODO", null);
    }

    @Test
    void listTasks_withoutStatusFilter_returnsAll() {
        Long projectId = 12L;
        doNothing().when(projectService).assertExists(projectId);
        when(taskRepository.findByProjectIdAndOptionalFilters(projectId, null, null))
                .thenReturn(List.of(new Task(projectId, "A", null, null, 0)));

        List<TaskService.TaskDto> results = taskService.listTasks(projectId, null);

        assertThat(results).hasSize(1);
        verify(taskRepository).findByProjectIdAndOptionalFilters(projectId, null, null);
    }

    @Test
    void listTasks_withCategoryFilter_delegatesToRepositoryFilter() {
        Long projectId = 12L;
        doNothing().when(projectService).assertExists(projectId);
        Task task = new Task(projectId, "任務 B", null, "TEST", 0);
        when(taskRepository.findByProjectIdAndOptionalFilters(projectId, null, "TEST"))
                .thenReturn(List.of(task));

        List<TaskService.TaskDto> results = taskService.listTasks(projectId, null, "test");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).category()).isEqualTo("TEST");
        verify(taskRepository).findByProjectIdAndOptionalFilters(projectId, null, "TEST");
    }

    @Test
    void listTasksByProjectRef_byProjectId_returnsSameResultAsListTasks() {
        Long projectId = 12L;
        doNothing().when(projectService).assertExists(projectId);
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE"));
        Task task = new Task(projectId, "任務 A", null, "BACKEND", 0);
        when(taskRepository.findByProjectIdAndOptionalFilters(projectId, null, null))
                .thenReturn(List.of(task));

        TaskService.ProjectTasksResult result =
                taskService.listTasksByProjectRef(projectId, null, null, null);

        assertThat(result.projectFound()).isTrue();
        assertThat(result.project().id()).isEqualTo(projectId);
        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().get(0).title()).isEqualTo("任務 A");
    }

    @Test
    void listTasksByProjectRef_byProjectName_resolvesToSameProject() {
        Long projectId = 12L;
        doNothing().when(projectService).assertExists(projectId);
        var project = new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        Task task = new Task(projectId, "任務 A", null, "BACKEND", 0);
        when(taskRepository.findByProjectIdAndOptionalFilters(projectId, null, null))
                .thenReturn(List.of(task));

        TaskService.ProjectTasksResult result =
                taskService.listTasksByProjectRef(null, "個人記帳 App", null, null);

        assertThat(result.projectFound()).isTrue();
        assertThat(result.project().id()).isEqualTo(projectId);
        assertThat(result.tasks()).hasSize(1);
    }

    @Test
    void listTasksByProjectRef_whenNeitherProvided_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> taskService.listTasksByProjectRef(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
    }

    @Test
    void listTasksByProjectRef_whenProjectNameMissing_returnsAvailableProjects() {
        when(projectService.findByNameIgnoreCase("不存在")).thenReturn(Optional.empty());
        when(projectService.listProjects()).thenReturn(List.of(
                new ProjectService.ProjectDto(1L, "現有專案", null, "ACTIVE")));

        TaskService.ProjectTasksResult result =
                taskService.listTasksByProjectRef(null, "不存在", null, null);

        assertThat(result.projectFound()).isFalse();
        assertThat(result.availableProjects()).extracting(ProjectService.ProjectDto::name)
                .containsExactly("現有專案");
    }

    @Test
    void updateStatus_legalTransition_writesLogAndPublishesEvent() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = claimedTask(projectId, taskId, "實作交易 CRUD API",
                "BACKEND", 0, "backend-dev");
        setField(task, "status", "BLOCKED");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.countByProjectIdAndStatus(projectId, "DONE")).thenReturn(3L);
        when(taskRepository.countByProjectId(projectId)).thenReturn(8L);
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE"));

        TaskService.TaskStatusChangeResult result = taskService.updateStatus(taskId, "IN_PROGRESS", null);

        assertThat(result.changed()).isTrue();
        assertThat(result.from()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(result.to()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getStatus()).isEqualTo("IN_PROGRESS");

        ArgumentCaptor<TaskLog> logCaptor = ArgumentCaptor.forClass(TaskLog.class);
        verify(taskLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getFromStatus()).isEqualTo("BLOCKED");
        assertThat(logCaptor.getValue().getToStatus()).isEqualTo("IN_PROGRESS");

        ArgumentCaptor<BoardEvent> eventCaptor = ArgumentCaptor.forClass(BoardEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("task.status_changed");
    }

    @Test
    void updateStatus_sameStatus_isNoOpAndDoesNotWriteLogOrPublish() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = new Task(projectId, "實作交易 CRUD API", null, "BACKEND", 0);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.countByProjectIdAndStatus(projectId, "DONE")).thenReturn(0L);
        when(taskRepository.countByProjectId(projectId)).thenReturn(8L);
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE"));

        TaskService.TaskStatusChangeResult result = taskService.updateStatus(taskId, "TODO", null);

        assertThat(result.changed()).isFalse();
        verify(taskLogRepository, never()).save(any(TaskLog.class));
        verify(eventPublisher, never()).publish(any(BoardEvent.class));
    }

    @Test
    void updateStatus_illegalTransition_throwsBoardExceptionWithStatusesInMessage() {
        Long taskId = 4L;
        Long projectId = 12L;
        Task task = new Task(projectId, "實作交易 CRUD API", null, "BACKEND", 0);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.updateStatus(taskId, "DONE", null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("TODO")
                .hasMessageContaining("DONE");

        verify(taskLogRepository, never()).save(any(TaskLog.class));
        verify(eventPublisher, never()).publish(any(BoardEvent.class));
    }

    @Test
    void updateStatus_whenTaskDoesNotExist_throwsBoardException() {
        when(taskRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.updateStatus(999L, "DONE", null))
                .isInstanceOf(BoardException.class);
    }

    @Test
    void claimNextTask_claimsFirstTodoAndWritesLogAndEvent() {
        Long projectId = 12L;
        Task candidate = taskWithId(projectId, 14L, "實作交易 CRUD API", "BACKEND", 0);
        Task claimed = claimedTask(projectId, 14L, "實作交易 CRUD API", "BACKEND",
                0, "backend-dev");
        var project = new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        when(taskRepository.findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                projectId, "BACKEND", "TODO")).thenReturn(List.of(candidate));
        when(taskDependencyRepository.findUnfinishedPrerequisites(List.of(14L)))
                .thenReturn(List.of());
        when(taskRepository.claimIfTodo(org.mockito.ArgumentMatchers.eq(14L),
                org.mockito.ArgumentMatchers.eq("backend-dev"), any(LocalDateTime.class),
                any(String.class)))
                .thenReturn(1);
        when(taskRepository.findById(14L)).thenReturn(Optional.of(claimed));

        TaskService.ClaimNextTaskResult result =
                taskService.claimNextTask("個人記帳 App", "backend", "backend-dev");

        assertThat(result.claimed()).isTrue();
        assertThat(result.task().assignee()).isEqualTo("backend-dev");
        assertThat(result.task().status()).isEqualTo("IN_PROGRESS");
        verify(taskLogRepository).save(any(TaskLog.class));
        verify(eventPublisher).publish(any(BoardEvent.class));
    }

    @Test
    void claimNextTask_retriesWhenCandidateLosesRace() {
        Long projectId = 12L;
        Task first = taskWithId(projectId, 14L, "第一筆", "BACKEND", 0);
        Task second = taskWithId(projectId, 15L, "第二筆", "BACKEND", 1);
        Task claimed = claimedTask(projectId, 15L, "第二筆", "BACKEND", 1, "backend-dev");
        var project = new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        when(taskRepository.findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                projectId, "BACKEND", "TODO"))
                .thenReturn(List.of(first, second), List.of(second));
        when(taskDependencyRepository.findUnfinishedPrerequisites(any())).thenReturn(List.of());
        when(taskRepository.claimIfTodo(org.mockito.ArgumentMatchers.eq(14L),
                org.mockito.ArgumentMatchers.eq("backend-dev"), any(LocalDateTime.class),
                any(String.class)))
                .thenReturn(0);
        when(taskRepository.claimIfTodo(org.mockito.ArgumentMatchers.eq(15L),
                org.mockito.ArgumentMatchers.eq("backend-dev"), any(LocalDateTime.class),
                any(String.class)))
                .thenReturn(1);
        when(taskRepository.findById(15L)).thenReturn(Optional.of(claimed));

        TaskService.ClaimNextTaskResult result =
                taskService.claimNextTask("個人記帳 App", "BACKEND", "backend-dev");

        assertThat(result.task().id()).isEqualTo(15L);
        verify(taskRepository, times(2))
                .findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                        projectId, "BACKEND", "TODO");
    }

    @Test
    void claimNextTask_skipsTaskWhosePrerequisiteIsUnfinished() {
        Long projectId = 12L;
        Task blocked = taskWithId(projectId, 14L, "改後端", "BACKEND", 0);
        Task free = taskWithId(projectId, 15L, "無相依", "BACKEND", 1);
        Task prerequisite = taskWithId(projectId, 9L, "設定環境", "INFRA", 0);
        Task claimed = claimedTask(projectId, 15L, "無相依", "BACKEND", 1, "backend-dev");
        var project = new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        when(taskRepository.findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                projectId, "BACKEND", "TODO")).thenReturn(List.of(blocked, free));
        when(taskDependencyRepository.findUnfinishedPrerequisites(List.of(14L, 15L)))
                .thenReturn(List.of(prerequisiteOf(14L, prerequisite)));
        when(taskRepository.claimIfTodo(org.mockito.ArgumentMatchers.eq(15L),
                org.mockito.ArgumentMatchers.eq("backend-dev"), any(LocalDateTime.class),
                any(String.class)))
                .thenReturn(1);
        when(taskRepository.findById(15L)).thenReturn(Optional.of(claimed));

        TaskService.ClaimNextTaskResult result =
                taskService.claimNextTask("個人記帳 App", "BACKEND", "backend-dev");

        assertThat(result.task().id()).isEqualTo(15L);
        verify(taskRepository, never()).claimIfTodo(
                org.mockito.ArgumentMatchers.eq(14L), any(), any(LocalDateTime.class), any());
    }

    @Test
    void claimNextTask_whenAllCandidatesBlocked_reportsWhatTheyAreWaitingFor() {
        Long projectId = 12L;
        Task blocked = taskWithId(projectId, 14L, "改後端", "BACKEND", 0);
        Task prerequisite = taskWithId(projectId, 9L, "設定環境", "INFRA", 0);
        var project = new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        when(taskRepository.findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                projectId, "BACKEND", "TODO")).thenReturn(List.of(blocked));
        when(taskDependencyRepository.findUnfinishedPrerequisites(List.of(14L)))
                .thenReturn(List.of(prerequisiteOf(14L, prerequisite)));

        TaskService.ClaimNextTaskResult result =
                taskService.claimNextTask("個人記帳 App", "BACKEND", "backend-dev");

        assertThat(result.claimed()).isFalse();
        assertThat(result.blockedByDependency()).isTrue();
        assertThat(result.blockedCandidates()).hasSize(1);
        TaskService.BlockedCandidate candidate = result.blockedCandidates().getFirst();
        assertThat(candidate.task().id()).isEqualTo(14L);
        assertThat(candidate.task().title()).isEqualTo("改後端");
        assertThat(candidate.waitingFor()).extracting(TaskService.TaskDto::id)
                .containsExactly(9L);
        verify(taskRepository, never()).claimIfTodo(any(), any(), any(LocalDateTime.class), any());
    }

    @Test
    void claimNextTask_whenNoTodoAtAll_isNotReportedAsBlocked() {
        Long projectId = 12L;
        var project = new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        when(taskRepository.findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                projectId, "BACKEND", "TODO")).thenReturn(List.of());

        TaskService.ClaimNextTaskResult result =
                taskService.claimNextTask("個人記帳 App", "BACKEND", "backend-dev");

        assertThat(result.claimed()).isFalse();
        assertThat(result.blockedByDependency()).isFalse();
    }

    @Test
    void createTasks_whenDependencyIndexOutOfRange_throws() {
        doNothing().when(projectService).assertExistsForUpdate(12L);

        List<TaskService.TaskInput> inputs = List.of(
                new TaskService.TaskInput("第一筆", null, "BACKEND", List.of(5), List.of()));

        assertThatThrownBy(() -> taskService.createTasks(12L, inputs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出本批次範圍");
    }

    @Test
    void createTasks_whenDependencyPointsForward_throws() {
        doNothing().when(projectService).assertExistsForUpdate(12L);

        List<TaskService.TaskInput> inputs = List.of(
                new TaskService.TaskInput("第一筆", null, "BACKEND", List.of(2), List.of()),
                new TaskService.TaskInput("第二筆", null, "BACKEND", List.of(), List.of()));

        assertThatThrownBy(() -> taskService.createTasks(12L, inputs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("排在自己之後");
    }

    @Test
    void createTasks_whenDependencyIsSelf_throws() {
        doNothing().when(projectService).assertExistsForUpdate(12L);

        List<TaskService.TaskInput> inputs = List.of(
                new TaskService.TaskInput("第一筆", null, "BACKEND", List.of(1), List.of()));

        assertThatThrownBy(() -> taskService.createTasks(12L, inputs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可依賴自己");
    }

    @Test
    void claimNextTask_whenProjectMissing_returnsProjectList() {
        when(projectService.findByNameIgnoreCase("不存在")).thenReturn(Optional.empty());
        when(projectService.listProjects()).thenReturn(List.of(
                new ProjectService.ProjectDto(1L, "現有專案", null, "ACTIVE")));

        TaskService.ClaimNextTaskResult result =
                taskService.claimNextTask("不存在", "DOC", "docs");

        assertThat(result.projectFound()).isFalse();
        assertThat(result.availableProjects()).extracting(ProjectService.ProjectDto::name)
                .containsExactly("現有專案");
        verify(taskRepository, never())
                .findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                        any(), any(), any());
    }

    @Test
    void updateStatus_toTodo_clearsClaimMetadata() {
        Long projectId = 12L;
        Task task = claimedTask(projectId, 4L, "任務", "BACKEND", 0, "backend-dev");
        setField(task, "status", "BLOCKED");
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));
        when(taskRepository.countByProjectIdAndStatus(projectId, "DONE")).thenReturn(0L);
        when(taskRepository.countByProjectId(projectId)).thenReturn(1L);
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        taskService.updateStatus(4L, "TODO", "歸還");

        assertThat(task.getStatus()).isEqualTo("TODO");
        assertThat(task.getAssignee()).isNull();
        assertThat(task.getClaimedAt()).isNull();
    }

    @Test
    void updateStatus_withoutStoredTokenHash_worksWithoutClaimToken_backwardCompatible() {
        // 部署前既有、以舊契約認領（沒有 claimTokenHash）的任務，仍要能用舊呼叫方式
        // （不帶 claimToken）完成操作，這是 #112 對「舊 server 契約」的相容承諾。
        Long projectId = 12L;
        Task task = claimedTask(projectId, 4L, "舊資料任務", "BACKEND", 0, "backend-dev");
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));
        when(taskRepository.countByProjectIdAndStatus(projectId, "DONE")).thenReturn(0L);
        when(taskRepository.countByProjectId(projectId)).thenReturn(1L);
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        TaskService.TaskStatusChangeResult result = taskService.updateStatus(4L, "DONE", null);

        assertThat(result.changed()).isTrue();
        assertThat(task.getStatus()).isEqualTo("DONE");
    }

    @Test
    void updateStatus_withStoredTokenHash_rejectsMissingToken() {
        Long projectId = 12L;
        Task task = claimedTask(projectId, 4L, "受保護任務", "BACKEND", 0, "backend-dev");
        task.setClaimTokenHash(claimTokenService.hash("正確的token"));
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.updateStatus(4L, "DONE", null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("claimToken");

        verify(taskLogRepository, never()).save(any(TaskLog.class));
        verify(eventPublisher, never()).publish(any(BoardEvent.class));
    }

    @Test
    void updateStatus_withStoredTokenHash_rejectsWrongToken() {
        Long projectId = 12L;
        Task task = claimedTask(projectId, 4L, "受保護任務", "BACKEND", 0, "backend-dev");
        task.setClaimTokenHash(claimTokenService.hash("正確的token"));
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.updateStatus(4L, "DONE", null, "別人猜的token"))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("不正確");

        verify(taskLogRepository, never()).save(any(TaskLog.class));
    }

    @Test
    void updateStatus_withStoredTokenHash_acceptsCorrectToken() {
        Long projectId = 12L;
        Task task = claimedTask(projectId, 4L, "受保護任務", "BACKEND", 0, "backend-dev");
        task.setClaimTokenHash(claimTokenService.hash("正確的token"));
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));
        when(taskRepository.countByProjectIdAndStatus(projectId, "DONE")).thenReturn(0L);
        when(taskRepository.countByProjectId(projectId)).thenReturn(1L);
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        TaskService.TaskStatusChangeResult result =
                taskService.updateStatus(4L, "DONE", null, "正確的token");

        assertThat(result.changed()).isTrue();
        assertThat(task.getStatus()).isEqualTo("DONE");
        // DONE 必須清空 token hash。
        assertThat(task.getClaimTokenHash()).isNull();
    }

    @Test
    void updateStatus_toBlocked_keepsTokenHash() {
        Long projectId = 12L;
        Task task = claimedTask(projectId, 4L, "受保護任務", "BACKEND", 0, "backend-dev");
        task.setClaimTokenHash(claimTokenService.hash("正確的token"));
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));
        when(taskRepository.countByProjectIdAndStatus(projectId, "DONE")).thenReturn(0L);
        when(taskRepository.countByProjectId(projectId)).thenReturn(1L);
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        taskService.updateStatus(4L, "BLOCKED", "卡住了", "正確的token");

        assertThat(task.getStatus()).isEqualTo("BLOCKED");
        assertThat(task.getClaimTokenHash()).isNotNull();
    }

    @Test
    void updateStatus_toTodo_clearsTokenHashEvenWithStoredToken() {
        Long projectId = 12L;
        Task task = claimedTask(projectId, 4L, "受保護任務", "BACKEND", 0, "backend-dev");
        setField(task, "status", "BLOCKED");
        task.setClaimTokenHash(claimTokenService.hash("正確的token"));
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));
        when(taskRepository.countByProjectIdAndStatus(projectId, "DONE")).thenReturn(0L);
        when(taskRepository.countByProjectId(projectId)).thenReturn(1L);
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        taskService.updateStatus(4L, "TODO", "歸還", "正確的token");

        assertThat(task.getStatus()).isEqualTo("TODO");
        assertThat(task.getClaimTokenHash()).isNull();
        assertThat(task.getAssignee()).isNull();
    }

    @Test
    void claimNextTask_storesOnlyHashAndReturnsPlaintextTokenOnce() {
        Long projectId = 12L;
        Task candidate = taskWithId(projectId, 14L, "任務", "BACKEND", 0);
        Task claimed = claimedTask(projectId, 14L, "任務", "BACKEND", 0, "backend-dev");
        var project = new ProjectService.ProjectDto(projectId, "個人記帳 App", null, "ACTIVE");
        when(projectService.findByNameIgnoreCase("個人記帳 App")).thenReturn(Optional.of(project));
        when(taskRepository.findByProjectIdAndCategoryAndStatusOrderBySortOrderAscIdAsc(
                projectId, "BACKEND", "TODO")).thenReturn(List.of(candidate));
        when(taskDependencyRepository.findUnfinishedPrerequisites(List.of(14L))).thenReturn(List.of());
        when(taskRepository.claimIfTodo(org.mockito.ArgumentMatchers.eq(14L),
                org.mockito.ArgumentMatchers.eq("backend-dev"), any(LocalDateTime.class),
                any(String.class)))
                .thenReturn(1);
        when(taskRepository.findById(14L)).thenReturn(Optional.of(claimed));

        TaskService.ClaimNextTaskResult result =
                taskService.claimNextTask("個人記帳 App", "BACKEND", "backend-dev");

        assertThat(result.claimToken()).isNotBlank();

        // task_log 的 note 只有認領者名稱，絕不含 token 原文。
        ArgumentCaptor<TaskLog> logCaptor = ArgumentCaptor.forClass(TaskLog.class);
        verify(taskLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getNote()).doesNotContain(result.claimToken());
        assertThat(logCaptor.getValue().getNote()).isEqualTo("認領者：backend-dev");

        // claimIfTodo 收到的第四個參數是 hash，不是原文。
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskRepository).claimIfTodo(org.mockito.ArgumentMatchers.eq(14L),
                org.mockito.ArgumentMatchers.eq("backend-dev"), any(LocalDateTime.class),
                hashCaptor.capture());
        assertThat(hashCaptor.getValue()).isNotEqualTo(result.claimToken());
        assertThat(hashCaptor.getValue()).isEqualTo(claimTokenService.hash(result.claimToken()));
    }

    @Test
    void resetClaimAsLeader_bypassesTokenAndClearsClaimMetadata() {
        Long projectId = 12L;
        Task task = claimedTask(projectId, 4L, "遺失token的任務", "BACKEND", 0, "backend-dev");
        task.setClaimTokenHash(claimTokenService.hash("再也拿不回來的token"));
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));
        when(taskRepository.countByProjectIdAndStatus(projectId, "DONE")).thenReturn(0L);
        when(taskRepository.countByProjectId(projectId)).thenReturn(1L);
        when(projectService.getById(projectId))
                .thenReturn(new ProjectService.ProjectDto(projectId, "專案", null, "ACTIVE"));

        TaskService.TaskStatusChangeResult result =
                taskService.resetClaimAsLeader(4L, "agent 中斷連線遺失 token");

        assertThat(result.changed()).isTrue();
        assertThat(task.getStatus()).isEqualTo("TODO");
        assertThat(task.getAssignee()).isNull();
        assertThat(task.getClaimedAt()).isNull();
        assertThat(task.getClaimTokenHash()).isNull();

        ArgumentCaptor<TaskLog> logCaptor = ArgumentCaptor.forClass(TaskLog.class);
        verify(taskLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getNote()).doesNotContain("再也拿不回來的token");
    }

    @Test
    void resetClaimAsLeader_whenTaskIsDone_throws() {
        Task task = claimedTask(12L, 4L, "任務", "BACKEND", 0, "backend-dev");
        setField(task, "status", "DONE");
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.resetClaimAsLeader(4L, null))
                .isInstanceOf(BoardException.class);
    }

    @Test
    void updateStatus_unclaimedTodoCannotStartWithoutClaimTool() {
        Task task = taskWithId(12L, 4L, "任務", "BACKEND", 0);
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.updateStatus(4L, "IN_PROGRESS", null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("claim_next_task");
    }

    @Test
    void updateStatus_unclaimedBlockedTaskCannotBypassClaimTool() {
        Task task = taskWithId(12L, 4L, "任務", "BACKEND", 0);
        setField(task, "status", "BLOCKED");
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.updateStatus(4L, "IN_PROGRESS", null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("claim_next_task");
    }

    @Test
    void updateStatus_unclaimedTodoCannotBeBlocked() {
        Task task = taskWithId(12L, 4L, "任務", "BACKEND", 0);
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.updateStatus(4L, "BLOCKED", null))
                .isInstanceOf(BoardException.class)
                .hasMessageContaining("不能標記為 BLOCKED");
    }

    /** 組出 findUnfinishedPrerequisites 的投影列：taskId 還在等 prerequisite。 */
    private static TaskDependencyRepository.UnfinishedPrerequisite prerequisiteOf(
            Long taskId, Task prerequisite) {
        return new TaskDependencyRepository.UnfinishedPrerequisite() {
            @Override
            public Long getTaskId() {
                return taskId;
            }

            @Override
            public Task getPrerequisite() {
                return prerequisite;
            }
        };
    }

    private static Task taskWithId(Long projectId, Long id, String title,
                                   String category, int sortOrder) {
        Task task = new Task(projectId, title, null, category, sortOrder);
        setField(task, "id", id);
        return task;
    }

    private static Task claimedTask(Long projectId, Long id, String title,
                                    String category, int sortOrder, String assignee) {
        Task task = taskWithId(projectId, id, title, category, sortOrder);
        setField(task, "status", "IN_PROGRESS");
        setField(task, "assignee", assignee);
        setField(task, "claimedAt", LocalDateTime.now());
        return task;
    }

    private static void setField(Task task, String fieldName, Object value) {
        try {
            var field = Task.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(task, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
