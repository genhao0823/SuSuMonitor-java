package command

import (
	"regexp"
	"strings"
	"testing"
)

// TestRenderWhitelistedTemplates 验证白名单模板按契约渲染 argv（command-protocol-v1.md 模板表）。
func TestRenderWhitelistedTemplates(t *testing.T) {
	cases := []struct {
		name       string
		templateID string
		params     map[string]string
		want       []string
	}{
		{"disk_free", "disk_free", nil, []string{"df", "-h"}},
		{"mem_free", "mem_free", nil, []string{"free", "-m"}},
		{"uptime", "uptime", nil, []string{"uptime"}},
		{"listening_ports", "listening_ports", nil, []string{"ss", "-tlnp"}},
		{"process_list", "process_list", nil, []string{"ps", "aux"}},
		{"top_snapshot", "top_snapshot", nil, []string{"top", "-b", "-n1"}},
		{"service_status", "service_status", map[string]string{"unit": "nginx.service"},
			[]string{"systemctl", "status", "nginx.service"}},
		{"service_logs", "service_logs", map[string]string{"unit": "nginx", "lines": "100"},
			[]string{"journalctl", "-u", "nginx", "-n", "100", "--no-pager"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			argv, err := Render(tc.templateID, tc.params)
			if err != nil {
				t.Fatalf("render %s: unexpected error %v", tc.templateID, err)
			}
			if len(argv) != len(tc.want) {
				t.Fatalf("render %s: argv %v, want %v", tc.templateID, argv, tc.want)
			}
			for i := range argv {
				if argv[i] != tc.want[i] {
					t.Fatalf("render %s: argv %v, want %v", tc.templateID, argv, tc.want)
				}
			}
		})
	}
}

// TestRenderRejectsUnknownTemplate 验证模板外命令一律拒绝。
func TestRenderRejectsUnknownTemplate(t *testing.T) {
	if _, err := Render("rm -rf /", nil); err == nil {
		t.Fatal("unknown template must be rejected")
	} else if got, ok := err.(*ErrUnknownTemplate); !ok || !strings.Contains(got.Error(), "rm -rf /") {
		t.Fatalf("expected ErrUnknownTemplate, got %v", err)
	}
}

// TestRenderRejectsInvalidParams 验证参数缺失、注入载荷与多余键均被拒绝。
func TestRenderRejectsInvalidParams(t *testing.T) {
	cases := []struct {
		name       string
		templateID string
		params     map[string]string
	}{
		{"missing unit", "service_status", nil},
		{"shell metachar in unit", "service_status", map[string]string{"unit": "nginx; rm -rf /"}},
		{"space in unit", "service_status", map[string]string{"unit": "nginx service"}},
		{"newline in unit", "service_status", map[string]string{"unit": "nginx\nx"}},
		{"non-numeric lines", "service_logs", map[string]string{"unit": "nginx", "lines": "abc"}},
		{"oversized lines", "service_logs", map[string]string{"unit": "nginx", "lines": "100000"}},
		{"extra key", "service_status", map[string]string{"unit": "nginx", "extra": "x"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := Render(tc.templateID, tc.params); err == nil {
				t.Fatalf("%s must be rejected", tc.name)
			}
		})
	}
}

// TestUnitPatternAllowsTypicalNames 验证 unit 参数放行合法 systemd 单元名。
func TestUnitPatternAllowsTypicalNames(t *testing.T) {
	pattern := regexp.MustCompile(`^[a-zA-Z0-9_.@:-]{1,128}$`)
	for _, name := range []string{"nginx.service", "user@1000.service", "systemd-journald", "a:b-c_d.e"} {
		if !pattern.MatchString(name) {
			t.Fatalf("unit %q should match", name)
		}
	}
}
