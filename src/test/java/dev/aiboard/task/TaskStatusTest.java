package dev.aiboard.task;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusTest {

    @ParameterizedTest
    @CsvSource({
            "TODO, IN_PROGRESS, true",
            "TODO, BLOCKED, true",
            "TODO, DONE, false",
            "TODO, TODO, true",

            "IN_PROGRESS, DONE, true",
            "IN_PROGRESS, BLOCKED, true",
            "IN_PROGRESS, TODO, false",
            "IN_PROGRESS, IN_PROGRESS, true",

            "BLOCKED, IN_PROGRESS, true",
            "BLOCKED, TODO, true",
            "BLOCKED, DONE, false",
            "BLOCKED, BLOCKED, true",

            "DONE, IN_PROGRESS, true",
            "DONE, TODO, false",
            "DONE, BLOCKED, false",
            "DONE, DONE, true",
    })
    void canTransitionTo_matchesTransitionMatrix(TaskStatus from, TaskStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }
}
