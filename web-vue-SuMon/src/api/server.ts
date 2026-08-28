import apiClient from '@/api/client'
import type {
  ApiResponse,
  ConfirmSshHostKeyRequest,
  CreateServerRequest,
  PageResult,
  Server,
  ServerQuery,
  ServerStatus,
  SshHostKey,
  SshHostKeyObservation,
  SshTestResult,
  UpdateServerRequest
} from '@/types/api'

/**
 * 查询服务器分页列表。支持 page/page_size/sort_by/sort_order 和 keyword 模糊检索。
 *
 * @param query 查询参数对象
 * @returns 分页结果
 */
export function listServers(
  query: ServerQuery = {}
): Promise<ApiResponse<PageResult<Server>>> {
  return apiClient
    .get<ApiResponse<PageResult<Server>>>('/servers', { params: query })
    .then((r) => r.data)
}

/**
 * 获取单台服务器公开 VO(不含凭据明文或密文)。
 *
 * @param id 服务器 ID
 */
export function getServer(id: number): Promise<ApiResponse<Server>> {
  return apiClient
    .get<ApiResponse<Server>>(`/servers/${id}`)
    .then((r) => r.data)
}

/**
 * 获取服务器状态快照(状态、Agent、上次心跳)。
 *
 * @param id 服务器 ID
 */
export function getServerStatus(id: number): Promise<ApiResponse<ServerStatus>> {
  return apiClient
    .get<ApiResponse<ServerStatus>>(`/servers/${id}/status`)
    .then((r) => r.data)
}

/**
 * 创建服务器。失败抛出 ApiBusinessError,字段级 40002 由调用方映射到表单。
 */
export function createServer(
  body: CreateServerRequest
): Promise<ApiResponse<Server>> {
  return apiClient
    .post<ApiResponse<Server>>('/servers', body)
    .then((r) => r.data)
}

/**
 * 更新服务器。PUT 要求提交完整基础字段;凭据字段省略表示保留原值。
 */
export function updateServer(
  id: number,
  body: UpdateServerRequest
): Promise<ApiResponse<Server>> {
  return apiClient
    .put<ApiResponse<Server>>(`/servers/${id}`, body)
    .then((r) => r.data)
}

/**
 * 软删除服务器(后端 OpenAPI 描述)。
 * 成功响应通常为 ApiResponse<null> 或 ApiResponse<{}>。
 */
export function deleteServer(id: number): Promise<ApiResponse<null>> {
  return apiClient
    .delete<ApiResponse<null>>(`/servers/${id}`)
    .then((r) => r.data)
}

/**
 * 触发目标服务器的 SSH 连接测试(后端 MVP-7 接入)。
 * 成功:ApiResponse<SshTestResult>;失败抛 ApiBusinessError。
 */
export function testSshConnection(id: number): Promise<ApiResponse<SshTestResult>> {
  return apiClient
    .post<ApiResponse<SshTestResult>>(`/servers/${id}/ssh/test`)
    .then((r) => r.data)
}

/**
 * 查询目标服务器最近的 SSH 连接测试历史(成功与失败均记录,最新在前)。
 *
 * @param id 服务器 ID
 */
export function listSshTestHistory(id: number): Promise<ApiResponse<SshTestResult[]>> {
  return apiClient
    .get<ApiResponse<SshTestResult[]>>(`/servers/${id}/ssh/test/history`)
    .then((r) => r.data)
}

/**
 * 首次确认或显式轮换服务器 SSH 主机公钥指纹(OpenAPI confirmServerSshHostKey)。
 *
 * 后端只做目标解析与 SSH 握手指纹比对,不发送任何登录凭据。
 * replace 仅在管理员通过可信带外渠道验证新指纹后置 true,用于显式覆盖已登记指纹。
 *
 * @param id 服务器 ID
 * @param body 期望指纹 + 是否显式轮换
 * @returns 登记结果,包含算法、指纹、本次操作类型(confirmed/rotated/unchanged)和验证时间
 */
export function confirmSshHostKey(
  id: number,
  body: ConfirmSshHostKeyRequest
): Promise<ApiResponse<SshHostKey>> {
  return apiClient
    .put<ApiResponse<SshHostKey>>(`/servers/${id}/ssh/host-key`, body)
    .then((r) => r.data)
}

/**
 * 只读观察目标主机当前公钥(OpenAPI observeServerSshHostKey)。
 * 后端连接目标主机读取实际公钥,不登记、不发送凭据;供"一键信任"确认前核对。
 *
 * @param id 服务器 ID
 */
export function observeSshHostKey(id: number): Promise<ApiResponse<SshHostKeyObservation>> {
  return apiClient
    .post<ApiResponse<SshHostKeyObservation>>(`/servers/${id}/ssh/host-key/observe`)
    .then((r) => r.data)
}