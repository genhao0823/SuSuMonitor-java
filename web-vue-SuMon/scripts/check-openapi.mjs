#!/usr/bin/env node
/**
 * OpenAPI 契约 lint 与 Java Controller 路径漂移检查脚本。
 *
 * 用途:校验 OpenAPI 3.0 结构、本地引用和 operationId，并与 Java Controller 路径对比。
 *
 * 校验项:
 *   1. 顶层必含 openapi / info / paths
 *   2. openapi 版本必须是 3.0.x
 *   3. info.title 与 info.version 必须非空字符串
 *   4. paths 至少含有一条端点，且每个操作具有 responses 和唯一 operationId
 *   5. 所有本地 JSON Pointer $ref 均可解析
 *   6. Java Controller 的 HTTP 方法和路径与静态 OpenAPI 一致
 *
 * 设计原则:
 *   - 纯只读:不修改任何 JSON 文件
 *   - 离线:不依赖网络
 *   - 零依赖:仅用 Node 内置模块
 *   - 退出码:0 全通过,1 有失败(CI 友好)
 *
 * 用法:`npm run openapi:check`
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = fileURLToPath(new URL('.', import.meta.url))
/**
 * 项目根目录 = web-vue-SuMon 的上一级,即仓库根 SuSuMonitor(Jvav)/。
 * scripts/ 在 web-vue-SuMon/scripts/,故向上两级。
 */
const REPO_ROOT = resolve(__dirname, '..', '..')
const OPENAPI_DIR = join(REPO_ROOT, 'docs-SuMon', 'OpenApi-SuMon')
const CONTROLLER_DIR = join(
  REPO_ROOT,
  'server-java-SuMon',
  'src',
  'main',
  'java',
  'com',
  'susumonitor',
  'server'
)
const HTTP_METHODS = new Set(['get', 'post', 'put', 'delete', 'patch', 'options', 'head'])

/**
 * 从原始对象读取字符串字段,允许任意路径。
 * 若路径不存在或不是非空字符串,返回 null。
 */
function readString(obj, ...keys) {
  let cur = obj
  for (const k of keys) {
    if (cur === null || cur === undefined || typeof cur !== 'object') {
      return null
    }
    cur = cur[k]
  }
  if (typeof cur !== 'string' || cur.length === 0) {
    return null
  }
  return cur
}

/** 将 JSON Pointer 转义片段恢复为对象键。 */
function decodePointerSegment(segment) {
  return segment.replaceAll('~1', '/').replaceAll('~0', '~')
}

/** 检查当前文档中的本地 JSON Pointer 是否可以解析。 */
function resolveLocalRef(root, ref) {
  if (!ref.startsWith('#/')) return false
  let current = root
  for (const segment of ref.slice(2).split('/').map(decodePointerSegment)) {
    if (current === null || typeof current !== 'object' || !(segment in current)) return false
    current = current[segment]
  }
  return true
}

/** 递归收集文档中的全部 $ref。 */
function collectRefs(value, refs) {
  if (Array.isArray(value)) {
    for (const item of value) collectRefs(item, refs)
    return
  }
  if (value === null || typeof value !== 'object') return
  if (typeof value.$ref === 'string') refs.push(value.$ref)
  for (const child of Object.values(value)) collectRefs(child, refs)
}

/** 递归扫描指定目录下的 Controller Java 文件。 */
function findControllerFiles(directory) {
  const files = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const target = join(directory, entry.name)
    if (entry.isDirectory()) files.push(...findControllerFiles(target))
    else if (entry.isFile() && entry.name.endsWith('Controller.java')) files.push(target)
  }
  return files
}

/** 从项目当前使用的简单 Mapping 注解提取实际 HTTP 方法和路径。 */
function collectControllerOperations() {
  const operations = new Set()
  for (const file of findControllerFiles(CONTROLLER_DIR)) {
    const source = readFileSync(file, 'utf8')
    const classIndex = source.search(/\bclass\s+\w+Controller\b/)
    if (classIndex < 0) continue
    const classPrefix = source.slice(0, classIndex)
    const baseMatches = [...classPrefix.matchAll(/@RequestMapping\(\s*"([^"]*)"\s*\)/g)]
    const basePath = baseMatches.at(-1)?.[1] || ''
    const mappingPattern = /@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*"([^"]*)"\s*\))?/g
    for (const match of source.slice(classIndex).matchAll(mappingPattern)) {
      const method = match[1].toUpperCase()
      const path = `${basePath}${match[2] || ''}` || '/'
      operations.add(`${method} ${path}`)
    }
  }
  return operations
}

/**
 * 校验单个 OpenAPI JSON 文件。
 */
function validateFile(filePath, globalOperationIds) {
  const fileName = filePath.split(/[\\/]/).pop() || filePath
  const errors = []
  let raw
  try {
    raw = readFileSync(filePath, 'utf8')
  } catch (e) {
    return {
      file: fileName,
      ok: false,
      errors: [`无法读取文件: ${e.message}`]
    }
  }
  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch (e) {
    return {
      file: fileName,
      ok: false,
      errors: [`JSON 解析失败: ${e.message}`]
    }
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return {
      file: fileName,
      ok: false,
      errors: ['顶层 JSON 必须是对象']
    }
  }
  const root = parsed

  // 1. 顶层必含 openapi / info / paths
  for (const key of ['openapi', 'info', 'paths']) {
    if (!(key in root)) {
      errors.push(`顶层缺少必填字段: ${key}`)
    }
  }

  // 2. openapi 必须是 3.0.x
  const openapiVer = readString(root, 'openapi')
  if (openapiVer !== null && !/^3\.0\.\d+$/.test(openapiVer)) {
    errors.push(
      `openapi 版本必须是 3.0.x,实际为 "${openapiVer}"`
    )
  }

  // 3. info.title / info.version 非空
  const title = readString(root, 'info', 'title')
  if (title === null && 'info' in root) {
    errors.push('info.title 缺失或为空')
  }
  const version = readString(root, 'info', 'version')
  if (version === null && 'info' in root) {
    errors.push('info.version 缺失或为空')
  }

  // 4. paths 至少含一条端点，操作必须包含唯一 operationId 和 responses。
  let endpointCount = 0
  const operations = new Set()
  const paths = root.paths
  if (paths !== undefined) {
    if (paths === null || typeof paths !== 'object' || Array.isArray(paths)) {
      errors.push('paths 必须是对象')
    } else {
      for (const pathKey of Object.keys(paths)) {
        if (!pathKey.startsWith('/')) errors.push(`路径必须以 / 开头: ${pathKey}`)
        const pathItem = paths[pathKey]
        if (
          pathItem !== null &&
          typeof pathItem === 'object' &&
          !Array.isArray(pathItem)
        ) {
          for (const method of Object.keys(pathItem)) {
            // OpenAPI 路径项允许的非端点字段(参数定义等)跳过
            if (
              ['parameters', 'summary', 'description', 'servers'].includes(method)
            ) {
              continue
            }
            if (HTTP_METHODS.has(method.toLowerCase())) {
              endpointCount += 1
              operations.add(`${method.toUpperCase()} ${pathKey}`)
              const operation = pathItem[method]
              if (operation === null || typeof operation !== 'object' || Array.isArray(operation)) {
                errors.push(`${method.toUpperCase()} ${pathKey} 的操作定义必须是对象`)
                continue
              }
              const operationId = readString(operation, 'operationId')
              if (operationId === null) {
                errors.push(`${method.toUpperCase()} ${pathKey} 缺少 operationId`)
              } else if (globalOperationIds.has(operationId)) {
                errors.push(`operationId 重复: ${operationId}`)
              } else {
                globalOperationIds.add(operationId)
              }
              if (operation.responses === null || typeof operation.responses !== 'object'
                  || Array.isArray(operation.responses) || Object.keys(operation.responses).length === 0) {
                errors.push(`${method.toUpperCase()} ${pathKey} 缺少 responses`)
              }
            }
          }
        }
      }
    }
  }
  if (endpointCount === 0) {
    errors.push('paths 中未找到任何 HTTP 端点(get/post/put/delete/patch 等)')
  }

  // 5. 当前契约只使用本地引用，所有引用必须能在同一文档中解析。
  const refs = []
  collectRefs(root, refs)
  for (const ref of refs) {
    if (!resolveLocalRef(root, ref)) errors.push(`无法解析本地引用: ${ref}`)
  }

  return {
    file: fileName,
    ok: errors.length === 0,
    errors,
    endpointCount,
    operations
  }
}

/**
 * 主流程:扫描 OpenAPI 目录、逐个校验、打印报告、返回退出码。
 */
function main() {
  let stat
  try {
    stat = statSync(OPENAPI_DIR)
  } catch (e) {
    process.stdout.write(
      `[openapi:check] 目录不存在: ${OPENAPI_DIR}\n` +
        `(错误: ${e.message})\n`
    )
    return 1
  }
  if (!stat.isDirectory()) {
    process.stdout.write(`[openapi:check] 路径不是目录: ${OPENAPI_DIR}\n`)
    return 1
  }

  const files = readdirSync(OPENAPI_DIR)
    .filter((name) => name.endsWith('.json'))
    .sort()
  if (files.length === 0) {
    process.stdout.write(
      `[openapi:check] 目录中无 .json 文件: ${OPENAPI_DIR}\n`
    )
    return 1
  }

  const operationIds = new Set()
  const results = files.map((name) => validateFile(join(OPENAPI_DIR, name), operationIds))
  const documentedOperations = new Set(results.flatMap((result) => [...(result.operations || [])]))
  const controllerOperations = collectControllerOperations()
  const missingFromOpenApi = [...controllerOperations].filter(
    (item) => !documentedOperations.has(item)
  )
  const missingFromCode = [...documentedOperations].filter(
    (item) => !controllerOperations.has(item)
  )

  process.stdout.write('OpenAPI 契约与 Controller 路径校验\n')
  process.stdout.write('================================\n\n')
  for (const r of results) {
    if (r.ok) {
      const count = r.endpointCount || 0
      process.stdout.write(
        `✓ ${r.file.padEnd(32)} (${count} endpoint${count === 1 ? '' : 's'})\n`
      )
    } else {
      process.stdout.write(`✗ ${r.file}  失败:\n`)
      for (const err of r.errors) {
        process.stdout.write(`    - ${err}\n`)
      }
    }
  }
  if (missingFromOpenApi.length > 0) {
    process.stdout.write('✗ Controller 已实现但 OpenAPI 缺失:\n')
    for (const operation of missingFromOpenApi) process.stdout.write(`    - ${operation}\n`)
  }
  if (missingFromCode.length > 0) {
    process.stdout.write('✗ OpenAPI 已声明但 Controller 缺失:\n')
    for (const operation of missingFromCode) process.stdout.write(`    - ${operation}\n`)
  }
  process.stdout.write('\n================================\n')
  const passed = results.filter((r) => r.ok).length
  process.stdout.write(`${passed} / ${results.length} 通过\n`)
  return passed === results.length && missingFromOpenApi.length === 0 && missingFromCode.length === 0 ? 0 : 1
}

process.exit(main())
