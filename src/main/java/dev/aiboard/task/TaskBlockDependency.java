package dev.aiboard.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 某次 task_block_event 快照當下關聯到的 blocking task（僅 DEPENDENCY 原因會有資料）。
 * 與 task_dependency（恆常的任務相依，用於 claim_next_task 前置守衛）語意不同：
 * 這裡只是「那次 block 事件宣稱在等誰」的快照，事件結束後仍完整保留、不會回頭
 * 影響 task_dependency 的相依判斷。
 */
@Entity
@Table(name = "task_block_dependency")
public class TaskBlockDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "block_event_id", nullable = false)
    private Long blockEventId;

    @Column(name = "blocking_task_id", nullable = false)
    private Long blockingTaskId;

    protected TaskBlockDependency() {
    }

    public TaskBlockDependency(Long blockEventId, Long blockingTaskId) {
        this.blockEventId = blockEventId;
        this.blockingTaskId = blockingTaskId;
    }

    public Long getId() {
        return id;
    }

    public Long getBlockEventId() {
        return blockEventId;
    }

    public Long getBlockingTaskId() {
        return blockingTaskId;
    }
}
