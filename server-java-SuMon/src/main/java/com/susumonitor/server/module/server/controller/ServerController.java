package com.susumonitor.server.module.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "servers", description = "Secure server inventory management")
@RestController
@RequestMapping("/api/servers")
@Validated
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
    @Operation(
            summary = "Create server",
            description = "Admin only. The primary credential matching ssh_auth_type is required. "
                    + "password and private_key are mutually exclusive, blank credentials are invalid, "
                    + "and responses never contain credential plaintext or ciphertext.",
            operationId = "createServer",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Server created"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter or credential combination (40002)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)"),
            @ApiResponse(responseCode = "409", description = "Server name, host, concurrent state, or delete constraint conflicts with the request (40900)"),
            @ApiResponse(responseCode = "500", description = "Internal server or database error (50000/50001)")
    })
    @PostMapping
    public com.susumonitor.server.common.ApiResponse<ServerVo> create(
            @Valid
            @RequestBody CreateServerRequest request) {
        return com.susumonitor.server.common.ApiResponse.success(serverService.create(request));
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
    @Operation(
            summary = "List servers",
            description = "Available to any authenticated user. Returns non-deleted servers "
                    + "without credential plaintext or ciphertext.",
            operationId = "listServers",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Server page returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "500", description = "Internal server or database error (50000/50001)")
    })
    @GetMapping
    public com.susumonitor.server.common.ApiResponse<PageResult<ServerVo>> list(
            @Parameter(description = "One-based page number.")
            @RequestParam(name = "page", defaultValue = "1")
            @Min(1) Integer page,
            @Parameter(description = "Number of items per page.")
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(1)
            @Max(100) Integer pageSize,
            @Parameter(description = "Optional keyword matched against server name, host or description.")
            @RequestParam(name = "keyword", required = false)
            @Size(max = 100) String keyword,
            @Parameter(description = "Sort field whitelist (id/name/host/status/created_at/updated_at). "
                    + "status orders by the persisted server status snapshot.")
            @RequestParam(name = "sort_by", defaultValue = "id")
            @Pattern(regexp = "^(id|name|host|status|created_at|updated_at)$") String sortBy,
            @Parameter(description = "Sort direction (asc/desc).")
            @RequestParam(name = "sort_order", defaultValue = "desc")
            @Pattern(regexp = "^(asc|desc)$") String sortOrder) {
        ServerQueryRequest request = new ServerQueryRequest();
        request.setPage(page);
        request.setPageSize(pageSize);
        request.setKeyword(keyword);
        request.setSortBy(sortBy);
        request.setSortOrder(sortOrder);
        return com.susumonitor.server.common.ApiResponse.success(serverService.list(request));
    }

    /**
     * 按正数 ID 获取服务器公开详情。
     *
     * @param serverId 服务器 ID
     * @return 服务器详情的统一成功响应
     */
    @Operation(
            summary = "Get server",
            description = "Available to any authenticated user. Returns a non-deleted server "
                    + "without credential plaintext or ciphertext.",
            operationId = "getServer",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Server returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)"),
            @ApiResponse(responseCode = "500", description = "Internal server or database error (50000/50001)")
    })
    @GetMapping("/{id}")
    public com.susumonitor.server.common.ApiResponse<ServerVo> get(
            @Parameter(description = "Server ID.")
            @PathVariable("id")
            @Positive Long serverId) {
        return com.susumonitor.server.common.ApiResponse.success(serverService.get(serverId));
    }

    /**
     * 按正数 ID 全量更新服务器，并返回更新后的公开信息。
     *
     * @param serverId 服务器 ID
     * @param request 更新服务器参数
     * @return 更新后服务器的统一成功响应
     */
    @Operation(
            summary = "Update server",
            description = "Admin only. PUT is a full replacement of all basic fields: name, host, "
                    + "description (required but may be empty), ssh_host, ssh_port, ssh_user, and "
                    + "ssh_auth_type. Credentials may be omitted only to retain the existing credential "
                    + "when ssh_auth_type is unchanged. If ssh_auth_type changes, the service requires "
                    + "the matching new primary credential. Submitting both credential types or an "
                    + "empty credential is invalid. Responses never contain credential plaintext or "
                    + "ciphertext.",
            operationId = "updateServer",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Server updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter or credential combination (40002)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)"),
            @ApiResponse(responseCode = "409", description = "Server name, host, concurrent state, or delete constraint conflicts with the request (40900)"),
            @ApiResponse(responseCode = "500", description = "Internal server or database error (50000/50001)")
    })
    @PutMapping("/{id}")
    public com.susumonitor.server.common.ApiResponse<ServerVo> update(
            @Parameter(description = "Server ID.")
            @PathVariable("id")
            @Positive Long serverId,
             @RequestBody UpdateServerRequest request) {
        if (!serverService.existsActive(serverId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        return com.susumonitor.server.common.ApiResponse.success(serverService.update(serverId, request));
    }

    /**
     * 按正数 ID 软删除服务器，并返回 data 为 null 的成功响应。
     *
     * @param serverId 服务器 ID
     * @return data 为 null 的统一成功响应
     */
    @Operation(
            summary = "Delete server",
            description = "Admin only. Soft-deletes the server; no credential plaintext or ciphertext is returned.",
            operationId = "deleteServer",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Server deleted"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)"),
            @ApiResponse(responseCode = "500", description = "Internal server or database error (50000/50001)")
    })
    @DeleteMapping("/{id}")
    public com.susumonitor.server.common.ApiResponse<Void> delete(
            @Parameter(description = "Server ID.")
            @PathVariable("id")
            @Positive Long serverId) {
        serverService.delete(serverId);
        return com.susumonitor.server.common.ApiResponse.success(null);
    }

    /**
     * 按正数 ID 获取服务器及 Agent 的状态快照。
     *
     * @param serverId 服务器 ID
     * @return 服务器状态快照的统一成功响应
     */
    @Operation(
            summary = "Get server status snapshot",
            description = "Available to any authenticated user. Returns the latest status snapshot "
                    + "stored in the database. It does not trigger and must not be interpreted as a "
                    + "real-time probe.",
            operationId = "getServerStatus",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stored server status snapshot returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)"),
            @ApiResponse(responseCode = "500", description = "Internal server or database error (50000/50001)")
    })
    @GetMapping("/{id}/status")
    public com.susumonitor.server.common.ApiResponse<ServerStatusVo> status(
            @Parameter(description = "Server ID.")
            @PathVariable("id")
            @Positive Long serverId) {
        return com.susumonitor.server.common.ApiResponse.success(serverService.status(serverId));
    }

    /**
     * 握手核对管理员带外取得的指纹，并首次确认或显式轮换 SSH 主机公钥。
     *
     * @param serverId 服务器 ID
     * @param request 主机公钥确认请求
     * @param operator 当前管理员认证快照
     * @return 主机公钥确认结果
     */
    @Operation(
            summary = "Confirm or rotate SSH host key",
            description = "Admin only. Performs target validation and an SSH handshake to compare "
                    + "the observed host key with expected_fingerprint, but never sends password, "
                    + "private key, passphrase, or any other login credential. A first confirmation "
                    + "stores the key, replace=true explicitly rotates a different stored key, and "
                    + "confirming the currently stored key is an idempotent review.",
            operationId = "confirmServerSshHostKey",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Host key confirmed, rotated, or reviewed without change"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter or credential combination (40002)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Not an admin (40300) or the SSH target violates the outbound access policy (40301)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)"),
            @ApiResponse(responseCode = "409", description = "Host key not registered, does not match, or changed concurrently (40900/40901/40902)"),
            @ApiResponse(responseCode = "429", description = "SSH connection test concurrency limit reached (42900)"),
            @ApiResponse(responseCode = "500", description = "Database operation failed (50001)"),
            @ApiResponse(responseCode = "502", description = "SSH handshake or stored credential authentication failed (50002/50003)"),
            @ApiResponse(responseCode = "504", description = "SSH DNS, TCP, handshake, authentication, or total operation timed out (50400)")
    })
    @PutMapping("/{id}/ssh/host-key")
    public com.susumonitor.server.common.ApiResponse<SshHostKeyVo> updateSshHostKey(
            @Parameter(description = "Server ID.")
            @PathVariable("id")
            @Positive Long serverId,
            @Valid
            @RequestBody UpdateSshHostKeyRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return com.susumonitor.server.common.ApiResponse.success(serverSshService.updateHostKey(serverId, request, operator.id()));
    }

    /**
     * 只读观察目标主机当前公钥，供管理员"一键信任"前核对。
     *
     * @param serverId 服务器 ID
     * @return 观察到的远端主机公钥信息
     */
    @Operation(
            summary = "Observe SSH host key",
            description = "Admin only. Connects to the target host and reads its actual host public "
                    + "key without registering or sending credentials. Returns the observed algorithm "
                    + "and fingerprint for the administrator to confirm before trusting.",
            operationId = "observeServerSshHostKey",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Observed host public key"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Not an admin (40300) or the SSH target violates the outbound access policy (40301)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)"),
            @ApiResponse(responseCode = "429", description = "SSH connection test concurrency limit reached (42900)"),
            @ApiResponse(responseCode = "500", description = "Database operation failed (50001)"),
            @ApiResponse(responseCode = "502", description = "SSH handshake or stored credential authentication failed (50002/50003)"),
            @ApiResponse(responseCode = "504", description = "SSH DNS, TCP, handshake, authentication, or total operation timed out (50400)")
    })
    @PostMapping("/{id}/ssh/host-key/observe")
    public com.susumonitor.server.common.ApiResponse<SshHostKeyObservationVo> observeSshHostKey(
            @Parameter(description = "Server ID.")
            @PathVariable("id")
            @Positive Long serverId) {
        return com.susumonitor.server.common.ApiResponse.success(serverSshService.observeHostKey(serverId));
    }

    /**
     * 使用已登记主机身份和服务器存储凭据执行一次无命令 SSH 认证测试。
     *
     * @param serverId 服务器 ID
     * @param requestBody 必须缺省的请求体
     * @return SSH 连接测试结果
     */
    @Operation(
            summary = "Test SSH authentication",
            description = "Admin only. Accepts no request body. Requires an already registered host "
                    + "key, validates the handshake against that exact key, and then tests the server's "
                    + "stored password or private_key credential. It never registers, replaces, or "
                    + "updates a host key and closes the connection without executing a command or "
                    + "creating a PTY.",
            operationId = "testServerSshConnection",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSH host key and stored credential verified"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Not an admin (40300) or the SSH target violates the outbound access policy (40301)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)"),
            @ApiResponse(responseCode = "409", description = "Host key is not registered (40901)"),
            @ApiResponse(responseCode = "429", description = "SSH connection test concurrency limit reached (42900)"),
            @ApiResponse(responseCode = "500", description = "Database operation failed (50001)"),
            @ApiResponse(responseCode = "502", description = "SSH handshake or stored credential authentication failed (50002/50003)"),
            @ApiResponse(responseCode = "504", description = "SSH DNS, TCP, handshake, authentication, or total operation timed out (50400)")
    })
    @PostMapping("/{id}/ssh/test")
    public com.susumonitor.server.common.ApiResponse<SshTestVo> testSshConnection(
            @Parameter(description = "Server ID.")
            @PathVariable("id")
            @Positive Long serverId,
            @RequestBody(required = false) JsonNode requestBody) {
        if (requestBody != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        return com.susumonitor.server.common.ApiResponse.success(serverSshService.testConnection(serverId));
    }

    /**
     * 按正数 ID 查询某服务器最近的 SSH 连接测试历史。
     *
     * @param serverId 服务器 ID
     * @return 最近测试历史列表
     */
    @Operation(
            summary = "List SSH test history",
            description = "Admin only. Returns the most recent SSH connection test records "
                    + "(success and failure) for a server, newest first.",
            operationId = "listServerSshTestHistory",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recent SSH test history"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)")
    })
    @GetMapping("/{id}/ssh/test/history")
    public com.susumonitor.server.common.ApiResponse<List<SshTestHistoryVo>> listSshTestHistory(
            @Parameter(description = "Server ID.")
            @PathVariable("id")
            @Positive Long serverId) {
        return com.susumonitor.server.common.ApiResponse.success(serverSshService.listTestHistory(serverId));
    }
}
