package com.susumonitor.server.module.admin.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.susumonitor.server.module.admin.service.AdminUserService;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.admin.vo.AdminUserVo;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxMapper;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.security.AuthenticatedUser;
import com.susumonitor.server.security.JwtTokenService;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.terminal.mapper.TerminalSessionMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;

// 启用 Spring Boot 测试上下文和 MockMvc，验证管理员用户审核 Controller 行为。
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
// 启用 MockMvc，使测试无需启动真实 HTTP 端口即可调用 Controller。
@AutoConfigureMockMvc
class AdminUserControllerTests {

    @Autowired
    private MockMvc mockMvc;

    // 使用模拟管理员用户服务，隔离 Controller 测试与真实数据库实现。
    @MockitoBean
    private AdminUserService adminUserService;

    // 提供系统就绪检查所需的数据源替身，避免排除数据库自动配置后上下文无法创建。
    @MockitoBean
    private DataSource dataSource;

    // 提供 MyBatis Mapper 替身，避免测试加载真实 MyBatis 会话工厂。
    @MockitoBean
    private UserMapper userMapper;

    // 提供初始化状态 Mapper 替身，避免加载真实 MyBatis 会话工厂。
    @MockitoBean
    private AuthBootstrapStateMapper authBootstrapStateMapper;

    // 提供服务器 Mapper 替身，避免新增 Mapper 扫描后加载真实 MyBatis 会话工厂。
    @MockitoBean
    private ServerMapper serverMapper;

    @MockitoBean
    private MetricsMapper metricsMapper;

    @MockitoBean
    private MetricsCleanupMapper metricsCleanupMapper;

    // 使用模拟告警 Mapper，避免告警模块 Mapper 扫描后创建真实 MyBatis 会话依赖。
    @MockitoBean
    private AlertRuleMapper alertRuleMapper;

    @MockitoBean
    private AlertRecordMapper alertRecordMapper;

    @MockitoBean
    private AlertStateMapper alertStateMapper;

    // 使用模拟终端 Mapper，避免 V12 Mapper 扫描后创建真实 MyBatis 会话依赖。
    @MockitoBean
    private TerminalSessionMapper terminalSessionMapper;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    // 提供 JWT 服务替身，使安全过滤器测试可控制 Token 解析结果。
    @MockitoBean
    private JwtTokenService jwtTokenService;
    @MockitoBean
    private OutboxMapper outboxMapper;
    @MockitoBean
    private ConsumeRecordMapper consumeRecordMapper;



    // 验证管理员可以获取用户分页列表并返回统一成功响应（status/keyword/page 参数透传）。
    @Test
    void listUsersShouldReturnSuccess() throws Exception {
        com.susumonitor.server.module.admin.vo.AdminUserVo adminUserVo = new com.susumonitor.server.module.admin.vo.AdminUserVo();
        adminUserVo.setId(2L);
        adminUserVo.setUsername("pending_user");
        adminUserVo.setRole("user");
        adminUserVo.setReviewStatus("pending");
        adminUserVo.setCreatedAt(OffsetDateTime.now());
        com.susumonitor.server.common.vo.PageResult<com.susumonitor.server.module.admin.vo.AdminUserVo> page =
                new com.susumonitor.server.common.vo.PageResult<>();
        page.setItems(List.of(adminUserVo));
        page.setTotal(1);
        page.setPage(1);
        page.setPageSize(20);
        Mockito.when(adminUserService.pageUsers("pending", "user", 1, 20)).thenReturn(page);
        Mockito.when(jwtTokenService.parseToken("admin-token"))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id"));
        Mockito.when(userMapper.selectAuthenticationUserById(1L)).thenReturn(adminUser());

        mockMvc.perform(get("/api/admin/users")
                        .param("status", "pending")
                        .param("keyword", "user")
                        .param("page", "1")
                        .param("page_size", "20")
                        .header("Authorization", "Bearer admin-token")
                        .header("X-Correlation-ID", "admin-pending-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", not(blankOrNullString())))
                .andExpect(header().string("X-Correlation-ID", "admin-pending-1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(2L))
                .andExpect(jsonPath("$.data.items[0].reviewStatus").value("pending"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());
    }

    // 验证非法 status 值返回参数错误 40002。
    @Test
    void invalidStatusShouldReturnBadRequest() throws Exception {
        authenticateAdmin();

        mockMvc.perform(get("/api/admin/users")
                        .param("status", "banned")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    // 验证批量审核通过返回处理统计（含失败明细）。
    @Test
    void batchApproveUsersShouldReturnResult() throws Exception {
        authenticateAdmin();
        com.susumonitor.server.module.admin.vo.BatchReviewResult result =
                new com.susumonitor.server.module.admin.vo.BatchReviewResult();
        result.setProcessed(2);
        result.setFailed(0);
        result.setFailedIds(List.of());
        Mockito.when(adminUserService.batchUpdateReviewStatus(List.of(2L, 3L), "approved", 1L))
                .thenReturn(result);

        mockMvc.perform(put("/api/admin/users/batch-approve")
                        .header("Authorization", "Bearer admin-token")
                        .contentType("application/json")
                        .content("{\"user_ids\":[2,3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.processed").value(2))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.failed_ids").isArray());
    }

    // 验证批量拒绝空列表返回参数错误。
    @Test
    void batchReviewEmptyIdsShouldReturnBadRequest() throws Exception {
        authenticateAdmin();

        mockMvc.perform(put("/api/admin/users/batch-approve")
                        .header("Authorization", "Bearer admin-token")
                        .contentType("application/json")
                        .content("{\"user_ids\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    // 验证管理员可以批准待审核用户并返回统一成功响应。
    @Test
    void approveUserShouldReturnSuccess() throws Exception {
        Mockito.when(jwtTokenService.parseToken("admin-token"))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id"));
        Mockito.when(userMapper.selectAuthenticationUserById(1L)).thenReturn(adminUser());
        Mockito.when(adminUserService.approveUser(2L, 1L)).thenReturn(reviewedUser(2L, "approved"));

        mockMvc.perform(put("/api/admin/users/2/approve")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reviewStatus").value("approved"));

        verify(adminUserService).approveUser(2L, 1L);
    }

    // 验证管理员可以拒绝待审核用户并返回统一成功响应。
    @Test
    void rejectUserShouldReturnSuccess() throws Exception {
        Mockito.when(jwtTokenService.parseToken("admin-token"))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id"));
        Mockito.when(userMapper.selectAuthenticationUserById(1L)).thenReturn(adminUser());
        Mockito.when(adminUserService.rejectUser(2L, 1L)).thenReturn(reviewedUser(2L, "rejected"));

        mockMvc.perform(put("/api/admin/users/2/reject")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reviewStatus").value("rejected"));

        verify(adminUserService).rejectUser(2L, 1L);
    }

    // 验证未认证请求访问管理员接口返回统一 401。
    @Test
    void unauthenticatedRequestShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    // 验证非正数审核目标 ID 返回统一 40002。
    @Test
    void invalidUserIdShouldReturnBadRequest() throws Exception {
        authenticateAdmin();

        mockMvc.perform(put("/api/admin/users/0/approve")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    // 验证不存在的审核目标返回 40400。
    @Test
    void missingUserShouldReturnNotFound() throws Exception {
        authenticateAdmin();
        Mockito.when(adminUserService.approveUser(99L, 1L))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(put("/api/admin/users/99/approve")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    // 验证重复或并发审核返回 40900。
    @Test
    void repeatedReviewShouldReturnConflict() throws Exception {
        authenticateAdmin();
        Mockito.when(adminUserService.rejectUser(2L, 1L))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_CONFLICT));

        mockMvc.perform(put("/api/admin/users/2/reject")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    // 创建管理员鉴权用户实体，供 Bearer 过滤器回查使用。
    private UserEntity adminUser() {
        UserEntity userEntity = new UserEntity();
        userEntity.setId(1L);
        userEntity.setUsername("admin");
        userEntity.setRole("admin");
        userEntity.setReviewStatus("approved");
        userEntity.setCreatedAt(LocalDateTime.now());
        return userEntity;
    }

    // 配置管理员 JWT 和数据库状态回查。
    private void authenticateAdmin() {
        Mockito.when(jwtTokenService.parseToken("admin-token"))
                .thenReturn(new JwtTokenService.ParsedToken(1L, "admin", "token-id"));
        Mockito.when(userMapper.selectAuthenticationUserById(1L)).thenReturn(adminUser());
    }

    // 创建审核成功响应。
    private CurrentUserVo reviewedUser(Long id, String status) {
        CurrentUserVo user = new CurrentUserVo();
        user.setId(id);
        user.setUsername("review_target");
        user.setRole("user");
        user.setReviewStatus(status);
        user.setReviewedAt(OffsetDateTime.now());
        user.setCreatedAt(OffsetDateTime.now());
        return user;
    }
}
