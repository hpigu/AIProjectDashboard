package dev.aiboard.config;

import dev.aiboard.common.TaskCategory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import static org.assertj.core.api.Assertions.*;

class WorkerCapacityTest {
    @Test void limitsAndProjectScopedSnapshots() {
        var defaults = new WorkerCapacity("3", "1", "1", "1", "1");
        for (var category : new TaskCategory[]{TaskCategory.FRONTEND, TaskCategory.TEST, TaskCategory.INFRA, TaskCategory.DOC})
            assertThat(defaults.snapshot(1, category, 0).limit()).isEqualTo(1);
        assertThat(defaults.snapshot(1, TaskCategory.BACKEND, 2).available()).isEqualTo(1);
        assertThat(defaults.snapshot(2, TaskCategory.BACKEND, 0).available()).isEqualTo(3);
        var reduced = new WorkerCapacity("1", "1", "1", "1", "1").snapshot(1, TaskCategory.BACKEND, 2);
        assertThat(reduced.available()).isZero();
        assertThat(reduced.full()).isTrue();
        assertThat(reduced.inProgress()).isEqualTo(2);
        assertThatThrownBy(() -> defaults.snapshot(1, TaskCategory.OTHER, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void springDefaultsAndOverride() {
        new ApplicationContextRunner().withUserConfiguration(WorkerCapacity.class).run(context -> {
            var config = context.getBean(WorkerCapacity.class);
            assertThat(config.snapshot(1, TaskCategory.BACKEND, 0).limit()).isEqualTo(3);
        });
        new ApplicationContextRunner().withUserConfiguration(WorkerCapacity.class)
                .withPropertyValues("board.capacity.backend=1").run(context ->
                        assertThat(context.getBean(WorkerCapacity.class).snapshot(1, TaskCategory.BACKEND, 0).limit()).isEqualTo(1));
    }

    @Test void applicationYamlReadsEnvironmentVariables() {
        var runner = new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(WorkerCapacity.class);
        runner.withPropertyValues("BOARD_CAPACITY_BACKEND=1").run(context ->
                assertThat(context.getBean(WorkerCapacity.class).snapshot(1, TaskCategory.BACKEND, 0).limit()).isEqualTo(1));
        runner.withPropertyValues("BOARD_CAPACITY_BACKEND=").run(context ->
                assertThat(context).hasFailed());
    }

    @Test void rejectsInvalidLimits() {
        for (String value : new String[]{"", "0", "01", "-1", "1.0", " 1", "2147483648", "abc", "1\n", "1\r", "1\r\n"})
            assertThatThrownBy(() -> new WorkerCapacity(value, "1", "1", "1", "1"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BOARD_CAPACITY_BACKEND");
    }
}
