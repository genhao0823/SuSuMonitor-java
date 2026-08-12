package com.susumonitor.server.module.alert.controller;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.GlobalExceptionHandler;
import com.susumonitor.server.common.RequestIdFilter;
import com.susumonitor.server.module.alert.dto.CreateAlertRuleRequest;
import com.susumonitor.server.module.alert.dto.UpdateAlertRuleRequest;
import com.susumonitor.server.module.alert.service.AlertRuleService;
import com.susumonitor.server.module.alert.vo.AlertRuleVo;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import com.susumonitor.server.module.metrics.mapper.IngestionCleanupMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.server.mapper.SshTestHistoryMapper;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordCleanupMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.terminal.mapper.TerminalSessionMapper;
import com.susumonitor.server.security.JwtTokenService;
import com.susumonitor.server.security.SecurityConfig;
import com.susumonitor.server.security.SecurityErrorHandler;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 验证告警规则 REST API 的权限、参数校验和响应契约。 */
@ActiveProfiles("test")
@WebMvcTest(AlertRuleController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class AlertRuleControllerTests {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String ADMIN_BEARER = "Bearer " + ADMIN_TOKEN;
    private static final String AUTHORIZATION = "Authorization";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertRuleService alertRuleService;

    @MockitoBean
    private JwtTokenService jwtTokenService;
    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private AuthBootstrapStateMapper authBootstrapStateMapper;
    @MockitoBean
    private ServerMapper serverMapper;

    // 替代全局 Mapper 扫描注册的 SSH 测试历史 Mapper，避免加载真实 MyBatis 会话工厂。
    @MockitoBean
    private SshTestHistoryMapper sshTestHistoryMapper;
    @MockitoBean
    private MetricsMapper metricsMapper;
    @MockitoBean
    private MetricsCleanupMapper metricsCleanupMapper;

    // 替代全局 Mapper 扫描注册的指标幂等接收记录清理 Mapper。
    @MockitoBean
    private IngestionCleanupMapper ingestionCleanupMapper;

    // 替代全局 Mapper 扫描注册的消费幂等记录清理 Mapper。
    @MockitoBean
    private ConsumeRecordCleanupMapper consumeRecordCleanupMapper;

    // 替代全局 Mapper 扫描注册的告警记录清理 Mapper。
    @MockitoBean
    private AlertRecordCleanupMapper alertRecordCleanupMapper;

    @MockitoBean
    private AlertRuleMapper alertRuleMapper;
    @MockitoBean
    private AlertRecordMapper alertRecordMapper;
    @MockitoBean
    private AlertNotificationMapper alertNotificationMapper;
    @MockitoBean
    private AlertStateMapper alertStateMapper;
    @MockitoBean
    private TerminalSessionMapper terminalSessionMapper;
    @MockitoBean
    private OutboxMapper outboxMapper;
    @MockitoBean
    private ConsumeRecordMapper consumeRecordMapper;



    /** admin 创建合法规则应返回 200。 */
    @Test
    void adminShouldCreateRule() throws Exception {
        authenticateAdmin();
        when(alertRuleService.createRule(any(CreateAlertRuleRequest.class), eq(1L)))
                .thenReturn(ruleVo(1L));

        mockMvc.perform(post("/api/alerts/rules")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"metric\":\"cpu\",\"operator\":\">\",\"threshold_value\":80,\"level\":\"warning\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.metric").value("cpu"));
    }

    /** 未认证创建规则应返回 401。 */
    @Test
    void unauthenticatedCreateShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/api/alerts/rules")
                        .contentType("application/json")
                        .content("{\"metric\":\"cpu\",\"operator\":\">\",\"threshold_value\":80,\"level\":\"warning\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    /** 缺少必填字段应返回 40002。 */
    @Test
    void missingFieldsShouldReturnInvalidParameter() throws Exception {
        authenticateAdmin();
        mockMvc.perform(post("/api/alerts/rules")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"metric\":\"cpu\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    /** 重复规则应返回 40900。 */
    @Test
    void duplicateRuleShouldReturnConflict() throws Exception {
        authenticateAdmin();
        doThrow(new BusinessException(ErrorCode.RESOURCE_CONFLICT))
                .when(alertRuleService).createRule(any(CreateAlertRuleRequest.class), eq(1L));

        mockMvc.perform(post("/api/alerts/rules")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"metric\":\"cpu\",\"operator\":\">\",\"threshold_value\":80,\"level\":\"warning\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    /** 更新不存在的规则应返回 40400。 */
    @Test
    void updateNonexistentShouldReturnNotFound() throws Exception {
        authenticateAdmin();
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(alertRuleService).updateRule(eq(999L), any(UpdateAlertRuleRequest.class));

        mockMvc.perform(put("/api/alerts/rules/999")
                        .header(AUTHORIZATION, ADMIN_BEARER)
                        .contentType("application/json")
                        .content("{\"threshold_value\":90,\"level\":\"critical\",\"enabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    /** 删除不存在的规则应返回 40400。 */
    @Test
    void deleteNonexistentShouldReturnNotFound() throws Exception {
        authenticateAdmin();
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(alertRuleService).deleteRule(999L);

        mockMvc.perform(delete("/api/alerts/rules/999")
                        .header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    /** 查询规则列表应返回 200。 */
    @Test
    void listRulesShouldReturnOk() throws Exception {
        authenticateAdmin();
        when(alertRuleService.listRules()).thenReturn(java.util.List.of(ruleVo(1L)));

        mockMvc.perform(get("/api/alerts/rules").header(AUTHORIZATION, ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(1));
    }

    private void authenticateAdmin() {
        when(jwtTokenService.parseToken(ADMIN_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id"));
        com.susumonitor.server.module.auth.entity.UserEntity user = new com.susumonitor.server.module.auth.entity.UserEntity();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("admin");
        user.setReviewStatus("approved");
        user.setCreatedAt(LocalDateTime.now());
        when(userMapper.selectAuthenticationUserById(1L)).thenReturn(user);
    }

    private AlertRuleVo ruleVo(Long id) {
        AlertRuleVo vo = new AlertRuleVo();
        vo.setId(id);
        vo.setServerId(1L);
        vo.setMetric("cpu");
        vo.setOperator(">");
        vo.setThresholdValue(new BigDecimal("80"));
        vo.setLevel("warning");
        vo.setEnabled(true);
        vo.setCreatedBy(1L);
        vo.setCreatedAt(OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, ZoneOffset.UTC));
        vo.setUpdatedAt(OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, ZoneOffset.UTC));
        return vo;
    }
}
