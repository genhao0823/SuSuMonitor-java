package com.susumonitor.server.module.ai.entity;

import java.time.LocalDateTime;

/**
 * 映射管理员个人 AI 服务商配置（一人一行，user_id 唯一）。
 *
 * <p>api_key 以 AES-256-GCM 密文信封存储，实体层只持有密文；
 * 明文仅由 {@code AiUserProviderConfigService} 在按用户解析 provider 时解出，
 * 不写入日志、审计表或 VO。</p>
 */
public class AiUserProviderConfigEntity {
    private Long id;
    private Long userId;
    private String provider;
    private String baseUrl;
    private String apiKeyCiphertext;
    private String model;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getApiKeyCiphertext() { return apiKeyCiphertext; }
    public void setApiKeyCiphertext(String value) { apiKeyCiphertext = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { enabled = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
