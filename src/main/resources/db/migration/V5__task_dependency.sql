CREATE TABLE task_dependency (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id             BIGINT NOT NULL,
    depends_on_task_id  BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    CONSTRAINT fk_task_dependency_task
        FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_dependency_depends_on
        FOREIGN KEY (depends_on_task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT uk_task_dependency UNIQUE (task_id, depends_on_task_id),
    CONSTRAINT ck_task_dependency_not_self CHECK (task_id <> depends_on_task_id)
);

CREATE INDEX idx_task_dependency_task ON task_dependency (task_id);
CREATE INDEX idx_task_dependency_depends_on ON task_dependency (depends_on_task_id);
