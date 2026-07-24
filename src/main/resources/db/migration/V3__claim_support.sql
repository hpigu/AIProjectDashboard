ALTER TABLE task ADD COLUMN assignee VARCHAR(60);
ALTER TABLE task ADD COLUMN claimed_at TIMESTAMP;

CREATE INDEX idx_task_claim_candidate
    ON task (project_id, category, status, sort_order);
