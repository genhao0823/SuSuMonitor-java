package com.susumonitor.server.module.command;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 命令模板注册表：command-protocol-v1.md §模板表 的 Java 侧镜像（单一来源为契约文档）。
 *
 * <p>纵深防御的第一道校验：AI 或手动请求只能引用本表模板 ID 并提供通过参数
 * 正则校验的具名参数；渲染结果即管理员审批时看到的人工预览命令。Go Agent
 * 执行前会做第二道独立校验（内建同表），任何一侧失守都不会执行模板外命令。</p>
 *
 * <p>模板清单版本 v2（2026-09-15）：在 L1 只读八条基础上新增 L2 低影响变更两条
 * （systemctl_reload / journalctl_vacuum，risk=medium）；渲染语义与 Go 侧统一为
 * 参数值在 argv 元素内的子串替换（值先过白名单正则完整匹配）。</p>
 */
@Component
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class CommandTemplateRegistry {

    /** 单个具名参数的校验规则：缺失、不匹配或模板未声明的多余键一律拒绝。 */
    public record ParamSpec(String name, Pattern pattern) {
    }

    /** 一条白名单命令模板：argv 元素可含 {name} 占位符，无 shell；risk 为协议冻结的风险等级。 */
    public record Template(String id, String[] argv, ParamSpec[] params, CommandRiskLevel risk) {
    }

    private static final Pattern UNIT_PATTERN = Pattern.compile("^[a-zA-Z0-9_.@:-]{1,128}$");
    private static final Pattern LINES_PATTERN = Pattern.compile("^[0-9]{1,4}$");

    // L2 变更类模板参数从严：reload 目标限定常驻服务单元（64 长度、不含 device/scope 类冒号）；
    // vacuum 天数限定 1-999 的十进制整数，禁止 0（无意义）与前导 0。
    private static final Pattern RELOAD_UNIT_PATTERN = Pattern.compile("^[a-zA-Z0-9@._-]{1,64}$");
    private static final Pattern VACUUM_DAYS_PATTERN = Pattern.compile("^[1-9][0-9]{0,2}$");

    private final Map<String, Template> templates = new LinkedHashMap<>();

    /** 构造注册表并装载契约冻结的模板（顺序即展示顺序；L1 只读全 low，L2 变更全 medium）。 */
    public CommandTemplateRegistry() {
        register(new Template("disk_free", new String[]{"df", "-h"}, new ParamSpec[0], CommandRiskLevel.LOW));
        register(new Template("mem_free", new String[]{"free", "-m"}, new ParamSpec[0], CommandRiskLevel.LOW));
        register(new Template("uptime", new String[]{"uptime"}, new ParamSpec[0], CommandRiskLevel.LOW));
        register(new Template("listening_ports", new String[]{"ss", "-tlnp"}, new ParamSpec[0], CommandRiskLevel.LOW));
        register(new Template("process_list", new String[]{"ps", "aux"}, new ParamSpec[0], CommandRiskLevel.LOW));
        register(new Template("top_snapshot", new String[]{"top", "-b", "-n1"}, new ParamSpec[0], CommandRiskLevel.LOW));
        register(new Template("service_status", new String[]{"systemctl", "status", "{unit}"},
                new ParamSpec[]{new ParamSpec("unit", UNIT_PATTERN)}, CommandRiskLevel.LOW));
        register(new Template("service_logs",
                new String[]{"journalctl", "-u", "{unit}", "-n", "{lines}", "--no-pager"},
                new ParamSpec[]{new ParamSpec("unit", UNIT_PATTERN), new ParamSpec("lines", LINES_PATTERN)},
                CommandRiskLevel.LOW));
        register(new Template("systemctl_reload", new String[]{"systemctl", "reload", "{unit}"},
                new ParamSpec[]{new ParamSpec("unit", RELOAD_UNIT_PATTERN)}, CommandRiskLevel.MEDIUM));
        register(new Template("journalctl_vacuum", new String[]{"journalctl", "--vacuum-time={days}d"},
                new ParamSpec[]{new ParamSpec("days", VACUUM_DAYS_PATTERN)}, CommandRiskLevel.MEDIUM));
    }

    private void register(Template template) {
        templates.put(template.id(), template);
    }

    /**
     * 查找模板；不存在返回 null（调用方转换为 template_unknown 语义）。
     */
    public Template find(String templateId) {
        return templateId == null ? null : templates.get(templateId);
    }

    /** 返回全部模板（注册顺序），供模板列表端点展示。 */
    public java.util.Collection<Template> all() {
        return java.util.Collections.unmodifiableCollection(templates.values());
    }

    /**
     * 校验参数并渲染人工预览命令行；失败抛出 COMMAND_PARAM_INVALID。
     *
     * <p>严格模式：缺少声明参数、值不匹配正则、模板未声明的多余键均拒绝。</p>
     */
    public String validateAndRender(String templateId, Map<String, String> params) {
        Template template = find(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.COMMAND_PARAM_INVALID);
        }
        Map<String, String> input = params == null ? Map.of() : params;
        String[] argv = template.argv();
        for (ParamSpec spec : template.params()) {
            String value = input.get(spec.name());
            if (value == null || !spec.pattern().matcher(value).matches()) {
                throw new BusinessException(ErrorCode.COMMAND_PARAM_INVALID);
            }
        }
        for (String key : input.keySet()) {
            boolean declared = false;
            for (ParamSpec spec : template.params()) {
                if (spec.name().equals(key)) {
                    declared = true;
                    break;
                }
            }
            if (!declared) {
                throw new BusinessException(ErrorCode.COMMAND_PARAM_INVALID);
            }
        }
        StringBuilder rendered = new StringBuilder();
        for (String part : argv) {
            if (rendered.length() > 0) {
                rendered.append(' ');
            }
            rendered.append(replacePlaceholders(part, template, input));
        }
        return rendered.toString();
    }

    /** 将单个 argv 元素中的 {name} 占位符替换为已校验参数。 */
    private String replacePlaceholders(String part, Template template, Map<String, String> params) {
        String result = part;
        for (ParamSpec spec : template.params()) {
            result = result.replace("{" + spec.name() + "}", params.get(spec.name()));
        }
        return result;
    }
}
