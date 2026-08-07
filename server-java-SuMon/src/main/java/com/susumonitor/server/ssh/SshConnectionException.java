package com.susumonitor.server.ssh;

/**
 * 表示 SSH 出站策略、主机身份、连接或认证阶段的稳定失败分类。
 */
public class SshConnectionException extends RuntimeException {

    private final Category category;

    /**
     * 创建不携带底层敏感消息的 SSH 失败。
     *
     * @param category 稳定失败分类
     */
    public SshConnectionException(Category category) {
        super(category.name());
        this.category = category;
    }

    /**
     * 创建保留内部原因但对外只暴露稳定分类的 SSH 失败。
     *
     * @param category 稳定失败分类
     * @param cause 内部诊断原因
     */
    public SshConnectionException(Category category, Throwable cause) {
        super(category.name(), cause);
        this.category = category;
    }

    /**
     * 返回稳定失败分类，供业务层决定 HTTP 状态码和响应文案。
     *
     * @return 稳定失败分类
     */
    public Category getCategory() {
        return category;
    }

    /** SSH 连接模块允许向业务层传播的失败分类。 */
    public enum Category {
        TARGET_FORBIDDEN,
        HOST_KEY_MISMATCH,
        STATE_CHANGED,
        RESOURCE_NOT_FOUND,
        DATABASE_ERROR,
        CONNECTION_LIMIT,
        TIMEOUT,
        AUTHENTICATION_FAILED,
        CONNECTION_FAILED
    }
}
