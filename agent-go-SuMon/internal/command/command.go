// Package command 实现 SuSuMonitor Agent 的受限命令执行器。
//
// 执行器只接受服务端下发的白名单模板 ID 与具名参数（command-protocol-v1.md），
// 以 argv 直接 exec（无 shell），并强制超时、输出截断与凭据样式脱敏。
package command

import (
	"fmt"
	"regexp"
	"strings"
	"time"
)

// 稳定错误原因枚举（command.result.error），与契约文档一一对应。
const (
	ErrReasonTemplateUnknown      = "template_unknown"
	ErrReasonParamInvalid         = "param_invalid"
	ErrReasonRateLimited          = "rate_limited"
	ErrReasonTimeout              = "timeout"
	ErrReasonExecutionError       = "execution_error"
	ErrReasonUnsupportedPlatform  = "unsupported_platform"
)

// ParamSpec 定义单个具名参数的校验规则。
type ParamSpec struct {
	// Name 是参数在模板 argv 中的占位符名（{name}）。
	Name string
	// Pattern 是参数值必须完整匹配的正则；匹配失败一律拒绝。
	Pattern *regexp.Regexp
}

// Template 是一条白名单命令模板：argv 前缀中可含 {name} 占位符。
type Template struct {
	// ID 是契约冻结的模板标识（command-protocol-v1.md §模板表）。
	ID string
	// Argv 是不带 shell 的参数数组，元素可含 {name} 占位符。
	Argv []string
	// Params 是允许的具名参数集合；模板声明之外的多余参数一律拒绝。
	Params []ParamSpec
}

// templates 是 Agent 内建命令模板表（与 command-protocol-v1.md §五 逐字一致）：
// L1 只读诊断（low）+ L2 低影响变更（medium，2026-09-15）。
//
// 风险分级与自动审批是 Java 侧审批策略概念，本表不携带 risk 字段；
// 本表是执行前的最终校验，任何一侧被绕过都不会执行模板之外的命令。
var templates = map[string]Template{
	"disk_free":        {ID: "disk_free", Argv: []string{"df", "-h"}},
	"mem_free":         {ID: "mem_free", Argv: []string{"free", "-m"}},
	"uptime":           {ID: "uptime", Argv: []string{"uptime"}},
	"listening_ports":  {ID: "listening_ports", Argv: []string{"ss", "-tlnp"}},
	"process_list":     {ID: "process_list", Argv: []string{"ps", "aux"}},
	"top_snapshot":     {ID: "top_snapshot", Argv: []string{"top", "-b", "-n1"}},
	"service_status": {ID: "service_status", Argv: []string{"systemctl", "status", "{unit}"},
		Params: []ParamSpec{{Name: "unit", Pattern: regexp.MustCompile(`^[a-zA-Z0-9_.@:-]{1,128}$`)}}},
	"service_logs": {ID: "service_logs", Argv: []string{"journalctl", "-u", "{unit}", "-n", "{lines}", "--no-pager"},
		Params: []ParamSpec{
			{Name: "unit", Pattern: regexp.MustCompile(`^[a-zA-Z0-9_.@:-]{1,128}$`)},
			{Name: "lines", Pattern: regexp.MustCompile(`^[0-9]{1,4}$`)},
		}},
	"systemctl_reload": {ID: "systemctl_reload", Argv: []string{"systemctl", "reload", "{unit}"},
		Params: []ParamSpec{{Name: "unit", Pattern: regexp.MustCompile(`^[a-zA-Z0-9@._-]{1,64}$`)}}},
	"journalctl_vacuum": {ID: "journalctl_vacuum", Argv: []string{"journalctl", "--vacuum-time={days}d"},
		Params: []ParamSpec{{Name: "days", Pattern: regexp.MustCompile(`^[1-9][0-9]{0,2}$`)}}},
}

// ErrUnknownTemplate 表示模板 ID 不在内建白名单内。
type ErrUnknownTemplate struct{ ID string }

func (e *ErrUnknownTemplate) Error() string {
	return fmt.Sprintf("template %q is not in the builtin whitelist", e.ID)
}

// ErrInvalidParam 表示参数缺失、越界或模板声明之外的多余键。
type ErrInvalidParam struct{ Param string }

func (e *ErrInvalidParam) Error() string {
	return fmt.Sprintf("parameter %q is missing, mismatched or not allowed", e.Param)
}

// Render 校验参数并渲染模板为可执行 argv；任何失败返回分类错误。
//
// 严格模式：缺少声明参数、值不匹配正则、或携带模板未声明的多余键均拒绝。
func Render(templateID string, params map[string]string) ([]string, error) {
	tmpl, ok := templates[templateID]
	if !ok {
		return nil, &ErrUnknownTemplate{ID: templateID}
	}
	argv := append([]string(nil), tmpl.Argv...)
	for _, spec := range tmpl.Params {
		value, present := params[spec.Name]
		if !present || !spec.Pattern.MatchString(value) {
			return nil, &ErrInvalidParam{Param: spec.Name}
		}
		argv = replaceArgv(argv, spec.Name, value)
	}
	// 模板未声明的多余键一律拒绝，防止隐藏注入面。
	for name := range params {
		allowed := false
		for _, spec := range tmpl.Params {
			if spec.Name == name {
				allowed = true
				break
			}
		}
		if !allowed {
			return nil, &ErrInvalidParam{Param: name}
		}
	}
	return argv, nil
}

// replaceArgv 把 argv 中所有 {name} 占位符替换为已校验的参数值。
//
// 子串替换（与 Java 侧渲染语义一致）：占位符可与固定前后缀共存于同一
// argv 元素（如 --vacuum-time={days}d）。安全性前提是 value 已通过白名单
// 正则完整匹配校验（拒绝元字符与空白），替换后仍是一个 argv 元素，
// 不重新切分、不经 shell 解析。
func replaceArgv(argv []string, name, value string) []string {
	placeholder := "{" + name + "}"
	out := make([]string, len(argv))
	for i, part := range argv {
		out[i] = strings.ReplaceAll(part, placeholder, value)
	}
	return out
}

// Config 是受限执行器的运行时配置。
type Config struct {
	// Enabled 控制是否接受 command.execute；关闭时回 unsupported_platform。
	Enabled bool
	// MaxTimeout 是单命令执行超时上限；请求超时取 min(请求, MaxTimeout)。
	MaxTimeout time.Duration
	// MaxOutputBytes 是 stdout/stderr 各自的截断上限。
	MaxOutputBytes int
	// RatePerMinute 是本机命令执行频次上限（简单固定窗口）。
	RatePerMinute int
}

// Result 是一次命令执行的完整结果，总是携带稳定原因枚举（成功时为空）。
type Result struct {
	Success    bool
	ExitCode   int
	Stdout     string
	Stderr     string
	Truncated  bool
	DurationMs int64
	Error      string
}
