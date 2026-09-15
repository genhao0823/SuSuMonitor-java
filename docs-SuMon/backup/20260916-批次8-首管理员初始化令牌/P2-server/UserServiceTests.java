package com.susumonitor.server.module.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.auth.dto.RegisterRequest;
import com.susumonitor.server.module.auth.dto.LoginRequest;
import com.susumonitor.server.module.auth.entity.AuthBootstrapStateEntity;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import com.susumonitor.server.module.auth.vo.LoginVo;
import com.susumonitor.server.security.JwtTokenService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

// 启用 Mockito 扩展，为测试初始化字段上的 Mock 对象。
@ExtendWith(MockitoExtension.class)
class UserServiceTests {

    // 创建 UserMapper 的模拟对象，隔离 UserService 对真实数据库的依赖。
    @Mock
    private UserMapper userMapper;

    // 创建初始化状态 Mapper 的模拟对象，验证首管理员事务编排。
    @Mock
    private AuthBootstrapStateMapper authBootstrapStateMapper;

    // 创建 PasswordEncoder 的模拟对象，隔离密码编码器的具体实现。
    @Mock
    private PasswordEncoder passwordEncoder;

    // 创建 JWT 服务模拟对象，隔离登录业务与 Token 实现。
    @Mock
    private JwtTokenService jwtTokenService;

    private UserService userService;

    // 在每个测试方法执行前创建待测试的 UserService 实例。
    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode("SUSUMONITOR_DUMMY_LOGIN_PASSWORD")).thenReturn("dummy-bcrypt-hash");
        userService = new UserServiceImpl(userMapper, authBootstrapStateMapper, passwordEncoder, jwtTokenService);
    }

    // 验证第一个用户注册后成为已审核的管理员。
    @Test
    void registerFirstUserShouldCreateApprovedAdmin() {
        RegisterRequest request = registerRequest("admin", "Password123");
        when(userMapper.selectByUsername("admin")).thenReturn(null);
        when(authBootstrapStateMapper.selectForUpdate()).thenReturn(bootstrapState(false));
        when(passwordEncoder.encode("Password123")).thenReturn("bcrypt-hash");
        when(userMapper.insert(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity userEntity = invocation.getArgument(0);
            userEntity.setId(1L);
            userEntity.setCreatedAt(LocalDateTime.now());
            return 1;
        });
        when(authBootstrapStateMapper.markAdminInitialized(any(), any())).thenReturn(1);

        CurrentUserVo result = userService.register(request);

        assertEquals(1L, result.getId());
        assertEquals("admin", result.getRole());
        assertEquals("approved", result.getReviewStatus());
        assertNotNull(result.getCreatedAt());
        verify(passwordEncoder).encode("Password123");
    }

    // 验证后续用户注册后成为待审核的普通用户。
    @Test
    void registerFollowingUserShouldCreatePendingUser() {
        RegisterRequest request = registerRequest("user", "Password123");
        when(userMapper.selectByUsername("user")).thenReturn(null);
        when(authBootstrapStateMapper.selectForUpdate()).thenReturn(bootstrapState(true));
        when(passwordEncoder.encode("Password123")).thenReturn("bcrypt-hash");
        when(userMapper.insert(any(UserEntity.class))).thenReturn(1);

        CurrentUserVo result = userService.register(request);

        assertEquals("user", result.getRole());
        assertEquals("pending", result.getReviewStatus());
    }

    // 验证重复用户名返回资源冲突异常。
    @Test
    void registerExistingUsernameShouldThrowConflict() {
        RegisterRequest request = registerRequest("admin", "Password123");
        when(userMapper.selectByUsername("admin")).thenReturn(new UserEntity());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userService.register(request));

        assertEquals(40900, exception.getErrorCode().getCode());
    }

    // 验证数据库唯一索引冲突在并发注册场景中仍被转换为资源冲突。
    @Test
    void registerDatabaseDuplicateKeyShouldThrowConflict() {
        RegisterRequest request = registerRequest("concurrent_user", "Password123");
        when(userMapper.selectByUsername("concurrent_user")).thenReturn(null);
        when(authBootstrapStateMapper.selectForUpdate()).thenReturn(bootstrapState(true));
        when(passwordEncoder.encode("Password123")).thenReturn("bcrypt-hash");
        when(userMapper.insert(any(UserEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate username"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userService.register(request));

        assertEquals(ErrorCode.RESOURCE_CONFLICT, exception.getErrorCode());
    }

    // 验证数据库保存密码哈希，响应对象不暴露密码哈希。
    @Test
    void registerShouldNotReturnPasswordHash() {
        RegisterRequest request = registerRequest("admin", "Password123");
        when(userMapper.selectByUsername(anyString())).thenReturn(null);
        when(authBootstrapStateMapper.selectForUpdate()).thenReturn(bootstrapState(false));
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(userMapper.insert(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity userEntity = invocation.getArgument(0);
            userEntity.setId(1L);
            return 1;
        });
        when(authBootstrapStateMapper.markAdminInitialized(any(), any())).thenReturn(1);

        CurrentUserVo result = userService.register(request);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("bcrypt-hash", captor.getValue().getPasswordHash());
        assertEquals("admin", result.getRole());
    }

    // 验证初始化状态记录缺失时拒绝注册，避免绕过首管理员锁。
    @Test
    void registerMissingBootstrapStateShouldThrowDatabaseError() {
        RegisterRequest request = registerRequest("admin", "Password123");
        when(userMapper.selectByUsername("admin")).thenReturn(null);
        when(authBootstrapStateMapper.selectForUpdate()).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userService.register(request));

        assertEquals(ErrorCode.DATABASE_ERROR, exception.getErrorCode());
    }

    // 验证首管理员状态更新失败时抛出数据库错误，使整个注册事务回滚。
    @Test
    void registerBootstrapUpdateFailureShouldThrowDatabaseError() {
        RegisterRequest request = registerRequest("admin", "Password123");
        when(userMapper.selectByUsername("admin")).thenReturn(null);
        when(authBootstrapStateMapper.selectForUpdate()).thenReturn(bootstrapState(false));
        when(passwordEncoder.encode("Password123")).thenReturn("bcrypt-hash");
        when(userMapper.insert(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity userEntity = invocation.getArgument(0);
            userEntity.setId(1L);
            return 1;
        });
        when(authBootstrapStateMapper.markAdminInitialized(any(), any())).thenReturn(0);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userService.register(request));

        assertEquals(ErrorCode.DATABASE_ERROR, exception.getErrorCode());
    }

    // 验证已审核管理员使用正确密码登录后获得 Bearer JWT。
    @Test
    void loginApprovedAdminShouldReturnToken() {
        LoginRequest request = loginRequest("admin", "Password123");
        UserEntity userEntity = loginUser("admin", "admin", "approved");
        when(userMapper.selectByUsername("admin")).thenReturn(userEntity);
        when(passwordEncoder.matches("Password123", "bcrypt-hash")).thenReturn(true);
        when(jwtTokenService.issueToken(1L, "admin"))
                .thenReturn(new JwtTokenService.IssuedToken("jwt-token", 259200L));

        LoginVo result = userService.login(request);

        assertEquals("jwt-token", result.getToken());
        assertEquals("Bearer", result.getTokenType());
        assertEquals(259200L, result.getExpiresIn());
        assertEquals("admin", result.getUser().getRole());
    }

    // 验证不存在的用户使用统一凭据错误，且不会签发 Token。
    @Test
    void loginMissingUserShouldReturnInvalidCredentials() {
        LoginRequest request = loginRequest("missing", "Password123");
        when(userMapper.selectByUsername("missing")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.login(request));

        assertEquals(ErrorCode.INVALID_USERNAME_OR_PASSWORD, exception.getErrorCode());
        verify(passwordEncoder).matches("Password123", "dummy-bcrypt-hash");
        verify(jwtTokenService, never()).issueToken(any(), anyString());
    }

    // 验证密码错误不暴露用户审核状态，也不会签发 Token。
    @Test
    void loginWrongPasswordShouldReturnInvalidCredentials() {
        LoginRequest request = loginRequest("pending_user", "WrongPassword");
        UserEntity userEntity = loginUser("pending_user", "user", "pending");
        when(userMapper.selectByUsername("pending_user")).thenReturn(userEntity);
        when(passwordEncoder.matches("WrongPassword", "bcrypt-hash")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.login(request));

        assertEquals(ErrorCode.INVALID_USERNAME_OR_PASSWORD, exception.getErrorCode());
        verify(jwtTokenService, never()).issueToken(any(), anyString());
    }

    // 验证凭据正确但待审核的用户返回禁止访问。
    @Test
    void loginPendingUserShouldReturnForbidden() {
        LoginRequest request = loginRequest("pending_user", "Password123");
        UserEntity userEntity = loginUser("pending_user", "user", "pending");
        when(userMapper.selectByUsername("pending_user")).thenReturn(userEntity);
        when(passwordEncoder.matches("Password123", "bcrypt-hash")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.login(request));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(jwtTokenService, never()).issueToken(any(), anyString());
    }

    // 验证异常角色不能获取 Token。
    @Test
    void loginInvalidRoleShouldReturnForbidden() {
        LoginRequest request = loginRequest("invalid_role", "Password123");
        UserEntity userEntity = loginUser("invalid_role", "owner", "approved");
        when(userMapper.selectByUsername("invalid_role")).thenReturn(userEntity);
        when(passwordEncoder.matches("Password123", "bcrypt-hash")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.login(request));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
    }


    private RegisterRequest registerRequest(String username, String password) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    // 创建登录请求测试数据。
    private LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    // 创建包含认证字段的用户测试数据。
    private UserEntity loginUser(String username, String role, String reviewStatus) {
        UserEntity userEntity = new UserEntity();
        userEntity.setId(1L);
        userEntity.setUsername(username);
        userEntity.setPasswordHash("bcrypt-hash");
        userEntity.setRole(role);
        userEntity.setReviewStatus(reviewStatus);
        userEntity.setCreatedAt(LocalDateTime.now());
        return userEntity;
    }

    // 创建测试所需的首管理员初始化状态。
    private AuthBootstrapStateEntity bootstrapState(boolean adminInitialized) {
        AuthBootstrapStateEntity state = new AuthBootstrapStateEntity();
        state.setId(1L);
        state.setAdminInitialized(adminInitialized);
        return state;
    }
}
