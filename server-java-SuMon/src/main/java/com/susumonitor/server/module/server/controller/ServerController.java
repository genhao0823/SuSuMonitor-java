package com.susumonitor.server.module.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.server.dto.CreateServerRequest;
import com.susumonitor.server.module.server.dto.ServerQueryRequest;
import com.susumonitor.server.module.server.dto.UpdateServerRequest;
import com.susumonitor.server.module.server.dto.UpdateSshHostKeyRequest;
import com.susumonitor.server.module.server.service.ServerService;
import com.susumonitor.server.module.server.service.ServerSshService;
import com.susumonitor.server.module.server.vo.ServerStatusVo;
import com.susumonitor.server.module.server.vo.ServerVo;
import com.susumonitor.server.module.server.vo.SshHostKeyObservationVo;
import com.susumonitor.server.module.server.vo.SshHostKeyVo;
import com.susumonitor.server.module.server.vo.SshTestHistoryVo;
import com.susumonitor.server.module.server.vo.SshTestVo;
import com.susumonitor.server.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收服务器管理 HTTP 请求，并将参数转换后委托给服务器业务服务。
 */
// 将当前类注册为 REST Controller，并将返回值写入 HTTP 响应体。
@RestController
// 为服务器管理接口统一增加 /api/servers 路径前缀。
@RequestMapping("/api/servers")
// 启用方法参数约束，使非法分页、排序和服务器 ID 返回参数错误。
@Validated
// 自动生成包含 final 字段的构造方法，用于构造方法依赖注入。
@RequiredArgsConstructor
public class ServerController {

    private final ServerService serverService;
    private final ServerSshService serverSshService;
    private final Validator validator;

    /**
     * 创建服务器并返回不含 SSH 凭据的公开信息。
     *
     * @param request 创建服务器参数
     * @return 新建服务器的统一成功响应
     */
    // 将 POST /api/servers 映射到服务器创建方法。
    @PostMapping
    public ApiResponse<ServerVo> create(
            // 触发创建请求 DTO 的 Bean Validation 校验。
            @Valid
            // 将 HTTP JSON 请求体反序列化为创建请求 DTO。
            @RequestBody CreateServerRequest request) {
        return ApiResponse.success(serverService.create(request));
    }

    /**
     * 按显式查询参数构造分页请求并读取服务器列表。
     *
     * @param page 页码
     * @param pageSize 每页数量
     * @param keyword 搜索关键词
     * @param sortBy 排序字段
     * @param sortOrder 排序方向
     * @return 服务器分页结果的统一成功响应
     */
    // 将 GET /api/servers 映射到服务器分页查询方法。
    @GetMapping
    public ApiResponse<PageResult<ServerVo>> list(
            // 将 page 查询参数绑定为页码，并在缺省时使用第一页。
            @RequestParam(name = "page", defaultValue = "1")
            // 限制页码最小值为 1。
            @Min(1) Integer page,
            // 将 page_size 查询参数绑定为每页数量，并在缺省时使用 20。
            @RequestParam(name = "page_size", defaultValue = "20")
            // 限制每页数量最小值为 1。
            @Min(1)
            // 限制每页数量最大值为 100。
            @Max(100) Integer pageSize,
            // 将可选 keyword 查询参数绑定为搜索关键词。
            @RequestParam(name = "keyword", required = false)
            // 限制搜索关键词最大长度为 100 个字符。
            @Size(max = 100) String keyword,
            // 将 sort_by 查询参数绑定为排序字段，并在缺省时按 ID 排序。
            @RequestParam(name = "sort_by", defaultValue = "id")
            // 只允许 Mapper 支持的排序字段，防止非法字段进入动态排序。
            @Pattern(regexp = "^(id|name|host|status|created_at|updated_at)$") String sortBy,
            // 将 sort_order 查询参数绑定为排序方向，并在缺省时降序排列。
            @RequestParam(name = "sort_order", defaultValue = "desc")
            // 只允许小写 asc 或 desc 排序方向。
            @Pattern(regexp = "^(asc|desc)$") String sortOrder) {
        ServerQueryRequest request = new ServerQueryRequest();
        request.setPage(page);
        request.setPageSize(pageSize);
        request.setKeyword(keyword);
        request.setSortBy(sortBy);
        request.setSortOrder(sortOrder);
        return ApiResponse.success(serverService.list(request));
    }

    /**
     * 按正数 ID 获取服务器公开详情。
     *
     * @param serverId 服务器 ID
     * @return 服务器详情的统一成功响应
     */
    // 将 GET /api/servers/{id} 映射到服务器详情查询方法。
    @GetMapping("/{id}")
    public ApiResponse<ServerVo> get(
            // 将 id 路径参数绑定为服务器 ID。
            @PathVariable("id")
            // 限制服务器 ID 必须大于 0。
            @Positive Long serverId) {
        return ApiResponse.success(serverService.get(serverId));
    }

    /**
     * 按正数 ID 全量更新服务器，并返回更新后的公开信息。
     *
     * @param serverId 服务器 ID
     * @param request 更新服务器参数
     * @return 更新后服务器的统一成功响应
     */
    // 将 PUT /api/servers/{id} 映射到服务器更新方法。
    @PutMapping("/{id}")
    public ApiResponse<ServerVo> update(
            // 将 id 路径参数绑定为服务器 ID。
            @PathVariable("id")
            // 限制服务器 ID 必须大于 0。
            @Positive Long serverId,
             // 将 HTTP JSON 请求体反序列化为更新请求 DTO。
             @RequestBody UpdateServerRequest request) {
        if (!serverService.existsActive(serverId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        return ApiResponse.success(serverService.update(serverId, request));
    }

    /**
     * 按正数 ID 软删除服务器，并返回 data 为 null 的成功响应。
     *
     * @param serverId 服务器 ID
     * @return data 为 null 的统一成功响应
     */
    // 将 DELETE /api/servers/{id} 映射到服务器软删除方法。
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            // 将 id 路径参数绑定为服务器 ID。
            @PathVariable("id")
            // 限制服务器 ID 必须大于 0。
            @Positive Long serverId) {
        serverService.delete(serverId);
        return ApiResponse.success(null);
    }

    /**
     * 按正数 ID 获取服务器及 Agent 的状态快照。
     *
     * @param serverId 服务器 ID
     * @return 服务器状态快照的统一成功响应
     */
    // 将 GET /api/servers/{id}/status 映射到服务器状态查询方法。
    @GetMapping("/{id}/status")
    public ApiResponse<ServerStatusVo> status(
            // 将 id 路径参数绑定为服务器 ID。
            @PathVariable("id")
            // 限制服务器 ID 必须大于 0。
            @Positive Long serverId) {
        return ApiResponse.success(serverService.status(serverId));
    }

    /**
     * 握手核对管理员带外取得的指纹，并首次确认或显式轮换 SSH 主机公钥。
     *
     * @param serverId 服务器 ID
     * @param request 主机公钥确认请求
     * @param operator 当前管理员认证快照
     * @return 主机公钥确认结果
     */
    // 将 PUT /api/servers/{id}/ssh/host-key 映射到主机公钥确认方法。
    @PutMapping("/{id}/ssh/host-key")
    public ApiResponse<SshHostKeyVo> updateSshHostKey(
            // 将 id 路径参数绑定为服务器 ID。
            @PathVariable("id")
            // 限制服务器 ID 必须大于 0。
            @Positive Long serverId,
            // 触发主机公钥请求 DTO 的 Bean Validation 校验。
            @Valid
            // 将 HTTP JSON 请求体反序列化为主机公钥请求 DTO。
            @RequestBody UpdateSshHostKeyRequest request,
            // 从 SecurityContext 注入已通过数据库状态回查的管理员身份。
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(serverSshService.updateHostKey(serverId, request, operator.id()));
    }

    /**
     * 只读观察目标主机当前公钥，供管理员"一键信任"前核对。
     *
     * @param serverId 服务器 ID
     * @return 观察到的远端主机公钥信息
     */
    // 将 POST /api/servers/{id}/ssh/host-key/observe 映射到主机公钥观察方法。
    @PostMapping("/{id}/ssh/host-key/observe")
    public ApiResponse<SshHostKeyObservationVo> observeSshHostKey(
            // 将 id 路径参数绑定为服务器 ID。
            @PathVariable("id")
            // 限制服务器 ID 必须大于 0。
            @Positive Long serverId) {
        return ApiResponse.success(serverSshService.observeHostKey(serverId));
    }

    /**
     * 使用已登记主机身份和服务器存储凭据执行一次无命令 SSH 认证测试。
     *
     * @param serverId 服务器 ID
     * @param requestBody 必须缺省的请求体
     * @return SSH 连接测试结果
     */
    // 将 POST /api/servers/{id}/ssh/test 映射到无请求体 SSH 连接测试方法。
    @PostMapping("/{id}/ssh/test")
    public ApiResponse<SshTestVo> testSshConnection(
            // 将 id 路径参数绑定为服务器 ID。
            @PathVariable("id")
            // 限制服务器 ID 必须大于 0。
            @Positive Long serverId,
            // 可选绑定 JSON 请求体，以便显式拒绝契约不允许的任何请求内容。
            @RequestBody(required = false) JsonNode requestBody) {
        if (requestBody != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        return ApiResponse.success(serverSshService.testConnection(serverId));
    }

    /**
     * 按正数 ID 查询某服务器最近的 SSH 连接测试历史。
     *
     * @param serverId 服务器 ID
     * @return 最近测试历史列表
     */
    // 将 GET /api/servers/{id}/ssh/test/history 映射到测试历史查询方法。
    @GetMapping("/{id}/ssh/test/history")
    public ApiResponse<List<SshTestHistoryVo>> listSshTestHistory(
            // 将 id 路径参数绑定为服务器 ID。
            @PathVariable("id")
            // 限制服务器 ID 必须大于 0。
            @Positive Long serverId) {
        return ApiResponse.success(serverSshService.listTestHistory(serverId));
    }
}
