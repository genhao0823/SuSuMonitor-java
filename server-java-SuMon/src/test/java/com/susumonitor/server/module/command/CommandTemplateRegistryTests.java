package com.susumonitor.server.module.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证命令模板注册表的白名单渲染与参数校验（与 command-protocol-v1.md 一致）。 */
class CommandTemplateRegistryTests {

    private final CommandTemplateRegistry registry = new CommandTemplateRegistry();

    /** 白名单模板按契约渲染 argv，人工预览命令即真实执行语义。 */
    @Test
    void shouldRenderWhitelistedTemplates() {
        assertEquals("df -h", registry.validateAndRender("disk_free", Map.of()));
        assertEquals("free -m", registry.validateAndRender("mem_free", Map.of()));
        assertEquals("uptime", registry.validateAndRender("uptime", Map.of()));
        assertEquals("ss -tlnp", registry.validateAndRender("listening_ports", Map.of()));
        assertEquals("ps aux", registry.validateAndRender("process_list", Map.of()));
        assertEquals("top -b -n1", registry.validateAndRender("top_snapshot", Map.of()));
        assertEquals("systemctl status nginx.service",
                registry.validateAndRender("service_status", Map.of("unit", "nginx.service")));
        assertEquals("journalctl -u nginx -n 100 --no-pager",
                registry.validateAndRender("service_logs", Map.of("unit", "nginx", "lines", "100")));
        // L2 变更类：占位符可与固定前后缀共存于同一 argv 元素（子串替换语义，与 Go 侧一致）。
        assertEquals("systemctl reload nginx",
                registry.validateAndRender("systemctl_reload", Map.of("unit", "nginx")));
        assertEquals("journalctl --vacuum-time=14d",
                registry.validateAndRender("journalctl_vacuum", Map.of("days", "14")));
    }

    /** 模板外命令、元字符注入、参数缺失/越界/多余键一律拒绝。 */
    @Test
    void shouldRejectTemplateEscapesAndInvalidParams() {
        assertThrows(BusinessException.class, () -> registry.validateAndRender("rm -rf /", Map.of()));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("service_status", Map.of("unit", "a; rm -rf /")));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("service_status", Map.of("unit", "$(whoami)")));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("service_status", Map.of("unit", "a b")));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("service_status", null));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("service_logs", Map.of("unit", "nginx", "lines", "-1")));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("service_logs",
                        Map.of("unit", "nginx", "lines", "100", "extra", "x")));
        // L2 变更类：元字符、空白、越界天数与多余键同样拒绝（2026-09-15）。
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("systemctl_reload", Map.of("unit", "nginx; reboot")));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("systemctl_reload", Map.of("unit", "nginx x")));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("systemctl_reload", Map.of("unit", "a".repeat(65))));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("systemctl_reload", null));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("journalctl_vacuum", Map.of("days", "0")));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("journalctl_vacuum", Map.of("days", "1000")));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("journalctl_vacuum", Map.of("days", "1d")));
        assertThrows(BusinessException.class,
                () -> registry.validateAndRender("journalctl_vacuum",
                        Map.of("days", "14", "extra", "x")));
    }

    /** 全部抛出为 COMMAND_PARAM_INVALID（40004），与错误码契约一致。 */
    @Test
    void invalidParamsShouldCarryErrorCode40004() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> registry.validateAndRender("unknown_template", Map.of()));
        assertEquals(ErrorCode.COMMAND_PARAM_INVALID, exception.getErrorCode());
    }

    /** 模板列表包含契约冻结的 8 个 L1 只读模板与 2 个 L2 变更类模板。 */
    @Test
    void shouldExposeAllContractTemplates() {
        Map<String, Boolean> ids = new HashMap<>();
        registry.all().forEach(template -> ids.put(template.id(), true));
        for (String id : new String[]{"disk_free", "mem_free", "uptime", "listening_ports",
                "process_list", "top_snapshot", "service_status", "service_logs",
                "systemctl_reload", "journalctl_vacuum"}) {
            assertTrue(ids.containsKey(id), "missing template " + id);
        }
        assertEquals(10, ids.size(), "registry must mirror the contract table exactly");
        assertDoesNotThrow(() -> registry.validateAndRender("service_logs",
                Map.of("unit", "user@1000.service", "lines", "9999")));
    }

    /** 风险分级不变量：L1 全部 low、L2 全部 medium、注册表不存在 high（防脏数据进审计快照）。 */
    @Test
    void shouldCarryContractRiskTiers() {
        for (CommandTemplateRegistry.Template template : registry.all()) {
            switch (template.id()) {
                case "systemctl_reload", "journalctl_vacuum" ->
                        assertEquals(CommandRiskLevel.MEDIUM, template.risk(),
                                "L2 template must be medium: " + template.id());
                default -> assertEquals(CommandRiskLevel.LOW, template.risk(),
                        "L1 template must be low: " + template.id());
            }
        }
    }
}
