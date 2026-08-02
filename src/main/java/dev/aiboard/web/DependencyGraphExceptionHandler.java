package dev.aiboard.web;

import dev.aiboard.task.DependencyCycleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts invalid stored graph data into an actionable read-only API response. */
@RestControllerAdvice
public class DependencyGraphExceptionHandler {

    @ExceptionHandler(DependencyCycleException.class)
    public ResponseEntity<DependencyGraphError> handleCycle(DependencyCycleException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new DependencyGraphError("DEPENDENCY_CYCLE", exception.getMessage()));
    }

    public record DependencyGraphError(String error, String message) {
    }
}
