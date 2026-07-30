package dev.aiboard.role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 角色的工作指引。project_id 為 NULL 代表通用指引，指定專案代表該專案的覆寫版本。
 * project_scope 這個唯一性欄位由資料庫端的 GENERATED ALWAYS 產生（COALESCE(project_id, 0)），
 * 不映射到 Entity，只用來讓 UNIQUE(name, project_id) 在 NULL 語意下也能擋重複。
 */
@Entity
@Table(name = "role")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 30)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Role() {
    }

    public Role(String name, String category, String instructions, Long projectId) {
        this.name = name;
        this.category = category;
        this.instructions = instructions;
        this.projectId = projectId;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getInstructions() {
        return instructions;
    }

    public Long getProjectId() {
        return projectId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void updateInstructions(String category, String instructions) {
        this.category = category;
        this.instructions = instructions;
        this.updatedAt = LocalDateTime.now();
    }
}
