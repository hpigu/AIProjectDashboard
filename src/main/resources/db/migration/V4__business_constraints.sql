ALTER TABLE project ADD COLUMN normalized_name VARCHAR(200);

UPDATE project
SET normalized_name = LOWER(TRIM(name));

ALTER TABLE project ALTER COLUMN normalized_name SET NOT NULL;
CREATE UNIQUE INDEX uk_project_normalized_name ON project (normalized_name);

UPDATE task
SET category = 'OTHER'
WHERE category IS NULL OR TRIM(category) = '';

ALTER TABLE task ALTER COLUMN category SET NOT NULL;
ALTER TABLE task ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE task
    ADD CONSTRAINT uk_task_project_sort_order UNIQUE (project_id, sort_order);
