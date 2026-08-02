package dev.aiboard.task;

import dev.aiboard.common.BoardException;

/** Stored dependency data is invalid and cannot be rendered as a directed acyclic graph. */
public class DependencyCycleException extends BoardException {

    public DependencyCycleException(String message) {
        super(message);
    }
}
