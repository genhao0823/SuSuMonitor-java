package com.susumonitor.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * 校验终端 WebSocket 载荷的共享边界，防止浏览器和 Agent 以不一致格式创建或操作会话。
 *
 * <p>校验器不执行业务授权、会话状态转换或终端命令，只保证进入后续业务层的数据满足冻结协议。</p>
 */
public final class TerminalProtocolValidator {

    /** 终端列数最小值。 */
    public static final int MIN_COLUMNS = 1;

    /** 终端列数最大值。 */
    public static final int MAX_COLUMNS = 300;

    /** 终端行数最小值。 */
    public static final int MIN_ROWS = 1;

    /** 终端行数最大值。 */
    public static final int MAX_ROWS = 100;

    /** 单条终端输入或输出解码后的最大字节数。 */
    public static final int MAX_DATA_BYTES = 16 * 1024;

    /** Agent 回传 Shell 标识的最大字符数。 */
    public static final int MAX_SHELL_LENGTH = 64;

    /** 会话关闭原因的最大字符数。 */
    public static final int MAX_CLOSE_REASON_LENGTH = 128;

    /** 终端错误文案的最大字符数。 */
    public static final int MAX_ERROR_MESSAGE_LENGTH = 256;

    private static final String SERVER_ID_FIELD = "server_id";
    private static final String SESSION_ID_FIELD = "session_id";
    private static final String COLUMNS_FIELD = "cols";
    private static final String ROWS_FIELD = "rows";
    private static final String DATA_FIELD = "data";
    private static final String SHELL_FIELD = "shell";
    private static final String REASON_FIELD = "reason";
    private static final String CODE_FIELD = "code";
    private static final String MESSAGE_FIELD = "message";
    private static final Set<String> MONITOR_MESSAGE_TYPES = Set.of(
            TerminalMessageType.TERMINAL_OPEN.value(),
            TerminalMessageType.TERMINAL_INPUT.value(),
            TerminalMessageType.TERMINAL_RESIZE.value(),
            TerminalMessageType.TERMINAL_CLOSE.value());
    private static final Set<String> AGENT_MESSAGE_TYPES = Set.of(
            TerminalMessageType.TERMINAL_OPENED.value(),
            TerminalMessageType.TERMINAL_OUTPUT.value(),
            TerminalMessageType.TERMINAL_CLOSED.value(),
            TerminalMessageType.TERMINAL_ERROR.value());

    /** 禁止实例化无状态协议校验器。 */
    private TerminalProtocolValidator() {
    }

    /**
     * 校验浏览器发送到 Monitor 通道的终端消息。
     *
     * @param message 浏览器终端消息
     */
    public static void validateMonitorMessage(TerminalMessage message) {
        validateCommonMessage(message, MONITOR_MESSAGE_TYPES);
        switch (message.type()) {
            case "terminal.open" -> validateOpenPayload(message.payload());
            case "terminal.input" -> validateInputPayload(message.payload());
            case "terminal.resize" -> validateResizePayload(message.payload());
            case "terminal.close" -> validateClosePayload(message.payload());
            default -> throw invalidPayload();
        }
    }

    /**
     * 校验 Agent 发送到 Agent 通道的终端消息。
     *
     * @param message Agent 终端消息
     */
    public static void validateAgentMessage(TerminalMessage message) {
        validateCommonMessage(message, AGENT_MESSAGE_TYPES);
        switch (message.type()) {
            case "terminal.opened" -> validateOpenedPayload(message.payload());
            case "terminal.output" -> validateOutputPayload(message.payload());
            case "terminal.closed" -> validateClosedPayload(message.payload());
            case "terminal.error" -> validateErrorPayload(message.payload());
            default -> throw invalidPayload();
        }
    }

    /**
     * 校验 Java 后端向 Agent 下发的终端控制消息。
     *
     * @param message Java 下发的终端消息
     */
    public static void validateServerToAgentMessage(TerminalMessage message) {
        validateCommonMessage(message, MONITOR_MESSAGE_TYPES);
        switch (message.type()) {
            case "terminal.open" -> validateServerOpenPayload(message.payload());
            case "terminal.input" -> validateServerInputPayload(message.payload());
            case "terminal.resize" -> validateServerResizePayload(message.payload());
            case "terminal.close" -> validateServerClosePayload(message.payload());
            default -> throw invalidPayload();
        }
    }

    /** 校验消息外层字段：类型允许、message_id 为 UUID、timestamp 为 UTC ISO-8601、payload 为对象。 */
    private static void validateCommonMessage(TerminalMessage message, Set<String> allowedTypes) {
        if (message == null || !allowedTypes.contains(message.type()) || !isUuid(message.messageId())
                || !isUtcTimestamp(message.timestamp()) || message.payload() == null || !message.payload().isObject()) {
            throw invalidPayload();
        }
    }

    /** 校验浏览器创建会话的载荷，含 server_id 和终端尺寸。 */
    private static void validateOpenPayload(JsonNode payload) {
        validateServerId(payload);
        validateDimensions(payload);
    }

    /** 校验 Java 下发给 Agent 的创建会话载荷，含 server_id、session_id 和终端尺寸。 */
    private static void validateServerOpenPayload(JsonNode payload) {
        validateServerId(payload);
        validateSessionId(payload);
        validateDimensions(payload);
    }

    /** 校验浏览器终端输入，含 session_id 和 Base64 数据。 */
    private static void validateInputPayload(JsonNode payload) {
        validateSessionId(payload);
        validateBase64Data(payload);
    }

    /** 校验 Java 下发给 Agent 的终端输入，在浏览器输入校验基础上追加 server_id。 */
    private static void validateServerInputPayload(JsonNode payload) {
        validateServerId(payload);
        validateInputPayload(payload);
    }

    /** 校验浏览器终端尺寸更新，含 session_id 和行列数。 */
    private static void validateResizePayload(JsonNode payload) {
        validateSessionId(payload);
        validateDimensions(payload);
    }

    /** 校验 Java 下发给 Agent 的终端尺寸更新，在浏览器尺寸校验基础上追加 server_id。 */
    private static void validateServerResizePayload(JsonNode payload) {
        validateServerId(payload);
        validateResizePayload(payload);
    }

    /** 校验浏览器关闭终端请求，含 session_id。 */
    private static void validateClosePayload(JsonNode payload) {
        validateSessionId(payload);
    }

    /** 校验 Java 下发给 Agent 的关闭终端请求，在浏览器关闭校验基础上追加 server_id。 */
    private static void validateServerClosePayload(JsonNode payload) {
        validateServerId(payload);
        validateClosePayload(payload);
    }

    /** 校验 Agent 成功创建终端的响应，含 server_id、session_id 和 shell 标识。 */
    private static void validateOpenedPayload(JsonNode payload) {
        validateServerId(payload);
        validateSessionId(payload);
        String shell = textValue(payload, SHELL_FIELD);
        if (shell == null || shell.length() > MAX_SHELL_LENGTH || !shell.matches("^[A-Za-z0-9._-]+$")) {
            throw invalidPayload();
        }
    }

    /** 校验 Agent 终端输出，含 server_id、session_id 和 Base64 数据。 */
    private static void validateOutputPayload(JsonNode payload) {
        validateServerId(payload);
        validateSessionId(payload);
        validateBase64Data(payload);
    }

    /** 校验 Agent 关闭终端的响应，含 server_id、session_id 和关闭原因。 */
    private static void validateClosedPayload(JsonNode payload) {
        validateServerId(payload);
        validateSessionId(payload);
        String reason = textValue(payload, REASON_FIELD);
        if (reason == null || reason.isBlank() || reason.length() > MAX_CLOSE_REASON_LENGTH) {
            throw invalidPayload();
        }
    }

    /** 校验 Agent 返回的终端错误，含 server_id、可选的 session_id、错误码和错误消息。 */
    private static void validateErrorPayload(JsonNode payload) {
        validateServerId(payload);
        JsonNode sessionId = payload.get(SESSION_ID_FIELD);
        if (sessionId != null && (!sessionId.isTextual() || !isUuid(sessionId.textValue()))) {
            throw invalidPayload();
        }
        JsonNode code = payload.get(CODE_FIELD);
        String message = textValue(payload, MESSAGE_FIELD);
        if (code == null || !code.canConvertToInt() || code.intValue() <= 0 || message == null || message.isBlank()
                || message.length() > MAX_ERROR_MESSAGE_LENGTH) {
            throw invalidPayload();
        }
    }

    /** 校验 payload 中的 server_id 为正数。 */
    private static void validateServerId(JsonNode payload) {
        JsonNode serverId = payload.get(SERVER_ID_FIELD);
        if (serverId == null || !serverId.canConvertToLong() || serverId.longValue() <= 0) {
            throw invalidPayload();
        }
    }

    /** 校验 payload 中的 session_id 为有效 UUID 格式。 */
    private static void validateSessionId(JsonNode payload) {
        if (!isUuid(textValue(payload, SESSION_ID_FIELD))) {
            throw invalidPayload();
        }
    }

    /** 校验 payload 中的终端行列数在协议限制范围内。 */
    private static void validateDimensions(JsonNode payload) {
        JsonNode columns = payload.get(COLUMNS_FIELD);
        JsonNode rows = payload.get(ROWS_FIELD);
        if (columns == null || rows == null || !columns.canConvertToInt() || !rows.canConvertToInt()
                || columns.intValue() < MIN_COLUMNS || columns.intValue() > MAX_COLUMNS
                || rows.intValue() < MIN_ROWS || rows.intValue() > MAX_ROWS) {
            throw invalidPayload();
        }
    }

    /** 校验 payload 中的 Base64 数据可解码且解码后不超过最大字节数限制。 */
    private static void validateBase64Data(JsonNode payload) {
        String data = textValue(payload, DATA_FIELD);
        if (data == null || data.isBlank()) {
            throw invalidPayload();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            if (decoded.length == 0 || decoded.length > MAX_DATA_BYTES) {
                throw invalidPayload();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidPayload();
        }
    }

    /** 安全获取 JSON 对象中指定字段的文本值，非文本或缺失时返回 null。 */
    private static String textValue(JsonNode payload, String fieldName) {
        JsonNode field = payload.get(fieldName);
        return field != null && field.isTextual() ? field.textValue() : null;
    }

    /** 校验字符串是否为有效的 UUID 格式。 */
    private static boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 校验字符串是否为 UTC 偏移的 ISO-8601 时间格式。 */
    private static boolean isUtcTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return OffsetDateTime.parse(value).getOffset().getTotalSeconds() == 0;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    /** 返回终端协议参数错误，供 Handler 映射为稳定 error 帧。 */
    private static BusinessException invalidPayload() {
        return new BusinessException(ErrorCode.TERMINAL_INVALID_PAYLOAD);
    }
}
