package com.susumonitor.server.module.auth.service;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.auth.dto.LoginRequest;
import com.susumonitor.server.module.auth.dto.RegisterRequest;
import com.susumonitor.server.module.auth.entity.AuthBootstrapStateEntity;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.module.auth.mapper.UserMapper;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import com.susumonitor.server.module.auth.vo.LoginVo;
import com.susumonitor.server.security.JwtTokenService;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 将当前类注册为 Spring Service Bean，承载用户相关业务逻辑。
@Service
public class UserServiceImpl implements UserService {

    private static final String ADMIN_ROLE = "admin";

    private static final String USER_ROLE = "user";

    private static final String APPROVED_STATUS = "approved";

    private static final String PENDING_STATUS = "pending";

    private static final ZoneId APPLICATION_ZONE = ZoneOffset.UTC;

    private static final String BEARER_TOKEN_TYPE = "Bearer";

    private static final String DUMMY_LOGIN_PASSWORD = "SUSUMONITOR_DUMMY_LOGIN_PASSWORD";

    private final UserMapper userMapper;

    private final AuthBootstrapStateMapper authBootstrapStateMapper;

    private final PasswordEncoder passwordEncoder;

    private final JwtTokenService jwtTokenService;

    private final String dummyPasswordHash;

    /**
     * 查询所有待审核用户（users 表所有权契约，管理面通过本接口访问）。
     *
     * @return 待审核用户实体列表
     */
    @Override
    public List<UserEntity> listPendingUsers() {
        return userMapper.selectPendingUsers();
    }

    /**
     * 分页查询待审核用户（users 表所有权契约，管理面通过本接口访问）。
     *
     * @param keyword  用户名模糊关键字，null/空白时不过滤
     * @param page     页码（从 1 起）
     * @param pageSize 每页大小
     * @return 分页结果（items/total/page/page_size）
     */
    @Override
    public com.susumonitor.server.common.vo.PageResult<UserEntity> pagePendingUsers(
            String keyword, int page, int pageSize) {
        String normalized = keyword == null ? null : keyword.trim();
        int offset = (page - 1) * pageSize;
        com.susumonitor.server.common.vo.PageResult<UserEntity> result =
                new com.susumonitor.server.common.vo.PageResult<>();
        result.setItems(userMapper.selectPagePendingUsers(normalized, offset, pageSize));
        result.setTotal(userMapper.countPendingUsers(normalized));
        result.setPage(page);
        result.setPageSize(pageSize);
        return result;
    }

    /**
     * 按 ID 查询用户，供管理面审核前置校验使用。
     *
     * @param userId 用户 ID
     * @return 用户实体，不存在时返回 null
     */
    @Override
    public UserEntity getReviewUserById(Long userId) {
        return userMapper.selectReviewUserById(userId);
    }

    /**
     * 更新用户审核状态，0 行表示状态已变化或行不存在。
     *
     * @param userId          被审核用户 ID
     * @param targetStatus    目标审核状态
     * @param operatorUserId  审核人 ID
     * @param reviewedAt      审核时间
     * @return 是否更新成功
     */
    @Override
    public boolean updateReviewStatus(Long userId, String targetStatus, Long operatorUserId, LocalDateTime reviewedAt) {
        return userMapper.updateReviewStatus(userId, targetStatus, operatorUserId, reviewedAt) == 1;
    }

    /**
     * 判断用户是否已审核通过（终端等跨模块能力校验用）。
     *
     * @param userId 用户 ID
     * @return 用户存在且 review_status=approved 时为 true
     */
    @Override
    public boolean isApprovedUser(Long userId) {
        UserEntity user = userMapper.selectAuthenticationUserById(userId);
        return user != null && APPROVED_STATUS.equals(user.getReviewStatus());
    }

    /**
     * 注入用户服务依赖，并创建仅用于统一不存在用户登录耗时的虚拟 BCrypt 哈希。
     *
     * @param userMapper 用户 Mapper
     * @param authBootstrapStateMapper 首管理员初始化状态 Mapper
     * @param passwordEncoder 密码编码器
     * @param jwtTokenService JWT 服务
     */
    public UserServiceImpl(
            UserMapper userMapper,
            AuthBootstrapStateMapper authBootstrapStateMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService) {
        this.userMapper = userMapper;
        this.authBootstrapStateMapper = authBootstrapStateMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.dummyPasswordHash = passwordEncoder.encode(DUMMY_LOGIN_PASSWORD);
    }

    // 在事务中锁定初始化状态并创建用户，保证并发注册最多产生一个首管理员。
    @Transactional
    public CurrentUserVo register(RegisterRequest request) {
        UserEntity existingUser = userMapper.selectByUsername(request.getUsername());
        if (existingUser != null) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }

        AuthBootstrapStateEntity bootstrapState = authBootstrapStateMapper.selectForUpdate();
        if (bootstrapState == null || bootstrapState.getAdminInitialized() == null) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR);
        }

        boolean firstUser = !bootstrapState.getAdminInitialized();
        UserEntity userEntity = new UserEntity();
        userEntity.setUsername(request.getUsername());
        userEntity.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userEntity.setRole(firstUser ? ADMIN_ROLE : USER_ROLE);
        userEntity.setReviewStatus(firstUser ? APPROVED_STATUS : PENDING_STATUS);
        userEntity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));

        try {
            if (userMapper.insert(userEntity) != 1) {
                throw new BusinessException(ErrorCode.DATABASE_ERROR);
            }
        } catch (DuplicateKeyException exception) {
            // 数据库唯一索引是并发注册时阻止重复用户名的最终保障。
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, exception);
        }

        if (firstUser && authBootstrapStateMapper.markAdminInitialized(
                userEntity.getId(), userEntity.getCreatedAt()) != 1) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR);
        }
        return toCurrentUserVo(userEntity);
    }

    /**
     * 校验登录凭据和最新用户状态，并为可登录用户签发 JWT。
     *
     * @param request 登录请求
     * @return Token、有效期和用户信息
     */
    public LoginVo login(LoginRequest request) {
        UserEntity userEntity = userMapper.selectByUsername(request.getUsername());
        String passwordHash = userEntity == null || userEntity.getPasswordHash() == null
                ? dummyPasswordHash : userEntity.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), passwordHash);
        if (userEntity == null || userEntity.getPasswordHash() == null || !passwordMatches) {
            throw new BusinessException(ErrorCode.INVALID_USERNAME_OR_PASSWORD);
        }
        if (!APPROVED_STATUS.equals(userEntity.getReviewStatus()) || !validRole(userEntity.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        JwtTokenService.IssuedToken issuedToken = jwtTokenService.issueToken(
                userEntity.getId(), userEntity.getUsername());
        LoginVo loginVo = new LoginVo();
        loginVo.setToken(issuedToken.token());
        loginVo.setTokenType(BEARER_TOKEN_TYPE);
        loginVo.setExpiresIn(issuedToken.expiresInSeconds());
        loginVo.setUser(toCurrentUserVo(userEntity));
        return loginVo;
    }


    /**
     * 角色只允许管理员或普通用户，拒绝异常数据库状态参与认证。
     *
     * @param role 用户角色
     * @return 角色是否合法
     */
    private boolean validRole(String role) {
        return ADMIN_ROLE.equals(role) || USER_ROLE.equals(role);
    }

    private CurrentUserVo toCurrentUserVo(UserEntity userEntity) {
        CurrentUserVo currentUserVo = new CurrentUserVo();
        currentUserVo.setId(userEntity.getId());
        currentUserVo.setUsername(userEntity.getUsername());
        currentUserVo.setRole(userEntity.getRole());
        currentUserVo.setReviewStatus(userEntity.getReviewStatus());
        currentUserVo.setReviewedAt(toOffsetDateTime(userEntity.getReviewedAt()));
        currentUserVo.setCreatedAt(toOffsetDateTime(userEntity.getCreatedAt()));
        return currentUserVo;
    }

    private OffsetDateTime toOffsetDateTime(java.time.LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(APPLICATION_ZONE).toOffsetDateTime();
    }
}
