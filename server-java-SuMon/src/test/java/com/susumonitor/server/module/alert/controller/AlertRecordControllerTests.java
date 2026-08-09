package com.susumonitor.server.module.alert.controller;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.GlobalExceptionHandler;
import com.susumonitor.server.common.RequestIdFilter;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.alert.service.AlertRecordService;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import com.susumonitor.server.module.metrics.mapper.IngestionCleanupMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.server.mapper.ServerMapper;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 验证告警记录 REST API 的分页查询和标记已读。 */
@ActiveProfiles("test")
@WebMvcTest(AlertRecordController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class AlertRecordControllerTests {

    private static final String USER_TOKEN = "user-token";
    private static final String USER_BEARER = "Bearer " + USER_TOKEN;
    private static final String AUTHORIZATION = "Authorization";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertRecordService alertRecordService;

    @MockitoBean
    private JwtTokenService jwtTokenService;
    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private AuthBootstrapStateMapper authBootstrapStateMapper;
    @MockitoBean
    private ServerMapper serverMapper;
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



    /** 分页查询应返回 200 和分页结构。 */
    @Test
    void listRecordsShouldReturnPageResult() throws Exception {
        authenticateUser();
        PageResult<AlertRecordVo> page = new PageResult<>();
        page.setItems(List.of(recordVo(1L)));
        page.setTotal(1L);
        page.setPage(1);
        page.setPageSize(20);
        when(alertRecordService.listRecords(eq(1L), eq(null), eq(1), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/alerts/records")
                        .header(AUTHORIZATION, USER_BEARER)
                        .param("server_id", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1));
    }

    /** 标记已读应返回 200。 */
    @Test
    void markAsReadShouldReturnOk() throws Exception {
        authenticateUser();
        mockMvc.perform(put("/api/alerts/records/1/read")
                        .header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 标记不存在记录已读应返回 40400。 */
    @Test
    void markNonexistentAsReadShouldReturnNotFound() throws Exception {
        authenticateUser();
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(alertRecordService).markAsRead(eq(999L), eq(2L));

        mockMvc.perform(put("/api/alerts/records/999/read")
                        .header(AUTHORIZATION, USER_BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    /** 未认证查询应返回 401。 */
    @Test
    void unauthenticatedListShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/alerts/records"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    private void authenticateUser() {
        when(jwtTokenService.parseToken(USER_TOKEN))
                .thenReturn(new JwtTokenService.ParsedToken(2L, "approved_user", "user-token-id"));
        com.susumonitor.server.module.auth.entity.UserEntity user = new com.susumonitor.server.module.auth.entity.UserEntity();
        user.setId(2L);
        user.setUsername("approved_user");
        user.setRole("user");
        user.setReviewStatus("approved");
        user.setCreatedAt(LocalDateTime.now());
        when(userMapper.selectAuthenticationUserById(2L)).thenReturn(user);
    }

    private AlertRecordVo recordVo(Long id) {
        AlertRecordVo vo = new AlertRecordVo();
        vo.setId(id);
        vo.setRuleId(1L);
        vo.setServerId(1L);
        vo.setMetric("cpu");
        vo.setCurrentValue(new BigDecimal("90.5"));
        vo.setThresholdValue(new BigDecimal("80"));
        vo.setLevel("warning");
        vo.setStatus("unread");
        vo.setTriggeredAt(OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, ZoneOffset.UTC));
        vo.setCreatedAt(OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, ZoneOffset.UTC));
        return vo;
    }
}
