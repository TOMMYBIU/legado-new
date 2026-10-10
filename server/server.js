#!/usr/bin/env node
'use strict'

/**
 * 阅读 Archive 联网同步服务端
 *
 * 零第三方依赖，仅需 Node.js >= 18。
 * 职责：账号注册/登录（签发 token） + 按命名空间存放客户端上传的同步快照。
 *
 * 数据目录结构（默认 ./data）：
 *   users.json                      账号表（scrypt 加盐哈希）
 *   users/<账号>/manifest.json      各命名空间的版本信息（updatedAt / hash / size / deviceId）
 *   users/<账号>/snapshots/<ns>.bin 快照本体（客户端打包的 zip）
 *   users/<账号>/history/<ns>-<ts>.bin 被覆盖前的历史快照（按 historyKeep 保留）
 */

const http = require('node:http')
const https = require('node:https')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')

const ROOT = __dirname
const CONFIG_PATH = path.join(ROOT, 'config.json')
const EXAMPLE_PATH = path.join(ROOT, 'config.example.json')

const DEFAULT_CONFIG = {
    host: '0.0.0.0',
    port: 8765,
    dataDir: './data',
    secret: '',
    tokenTtlDays: 30,
    allowRegister: true,
    maxSnapshotBytes: 64 * 1024 * 1024,
    historyKeep: 3,
    // 同时配置证书与私钥时改用 HTTPS（路径相对 server 目录）
    tlsCert: '',
    tlsKey: '',
    namespaces: ['sources', 'shelf', 'settings', 'bookmarks']
}

let config = null
let dataDir = ''
let usersFile = ''

// ---------------------------------------------------------------- 配置

function loadConfig() {
    let raw = {}
    if (fs.existsSync(CONFIG_PATH)) {
        raw = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'))
    } else if (fs.existsSync(EXAMPLE_PATH)) {
        raw = JSON.parse(fs.readFileSync(EXAMPLE_PATH, 'utf8'))
        log('未找到 config.json，使用 config.example.json 的默认值')
    }
    const merged = Object.assign({}, DEFAULT_CONFIG, raw)
    if (process.env.SYNC_HOST) merged.host = process.env.SYNC_HOST
    if (process.env.SYNC_PORT) merged.port = Number(process.env.SYNC_PORT)
    if (process.env.SYNC_DATA_DIR) merged.dataDir = process.env.SYNC_DATA_DIR
    if (process.env.SYNC_SECRET) merged.secret = process.env.SYNC_SECRET
    if (process.env.SYNC_TLS_CERT) merged.tlsCert = process.env.SYNC_TLS_CERT
    if (process.env.SYNC_TLS_KEY) merged.tlsKey = process.env.SYNC_TLS_KEY
    if (process.env.SYNC_ALLOW_REGISTER) {
        merged.allowRegister = process.env.SYNC_ALLOW_REGISTER !== '0'
    }
    merged.port = Number(merged.port) || DEFAULT_CONFIG.port
    merged.tokenTtlDays = Number(merged.tokenTtlDays) || DEFAULT_CONFIG.tokenTtlDays
    merged.maxSnapshotBytes = Number(merged.maxSnapshotBytes) || DEFAULT_CONFIG.maxSnapshotBytes
    merged.historyKeep = Math.max(0, Number(merged.historyKeep) || 0)
    if (!Array.isArray(merged.namespaces) || merged.namespaces.length === 0) {
        merged.namespaces = DEFAULT_CONFIG.namespaces.slice()
    }
    if (!merged.secret) {
        merged.secret = crypto.randomBytes(32).toString('hex')
        log('已在 config.json 中生成随机 secret')
    }
    // 始终把最终配置写回 config.json，方便运维查看与修改
    const persist = Object.assign({}, merged, { secret: merged.secret })
    fs.writeFileSync(CONFIG_PATH, JSON.stringify(persist, null, 2), 'utf8')
    return merged
}

function log(...args) {
    const time = new Date().toISOString()
    console.log(`[${time}]`, ...args)
}

// ---------------------------------------------------------------- 存储

function readJson(file, fallback) {
    try {
        if (!fs.existsSync(file)) return fallback
        return JSON.parse(fs.readFileSync(file, 'utf8'))
    } catch (e) {
        log(`读取 ${file} 失败: ${e.message}`)
        return fallback
    }
}

function writeJson(file, value) {
    mkdirp(path.dirname(file))
    const tmp = `${file}.tmp`
    fs.writeFileSync(tmp, JSON.stringify(value, null, 2), 'utf8')
    fs.renameSync(tmp, file)
}

function mkdirp(dir) {
    fs.mkdirSync(dir, { recursive: true })
}

// ---------------------------------------------------------------- 账号

const USERNAME_PATTERN = /^[A-Za-z0-9_.@-]{1,64}$/

function normalizeUsername(name) {
    const username = String(name || '').trim()
    if (!username || username === '.' || username === '..') return null
    if (!USERNAME_PATTERN.test(username)) return null
    return username
}

function loadUsers() {
    return readJson(usersFile, { version: 1, users: {} })
}

function saveUsers(db) {
    writeJson(usersFile, db)
}

function hashPassword(password, salt) {
    return crypto.scryptSync(password, salt, 64).toString('hex')
}

function createUser(username, password) {
    const db = loadUsers()
    if (db.users[username]) return { error: '账号已存在' }
    const salt = crypto.randomBytes(16).toString('hex')
    const now = Date.now()
    db.users[username] = {
        salt,
        hash: hashPassword(password, salt),
        createdAt: now,
        updatedAt: now
    }
    saveUsers(db)
    return { ok: true }
}

function verifyUser(username, password) {
    const db = loadUsers()
    const user = db.users[username]
    if (!user) return false
    const hash = hashPassword(password, user.salt)
    const a = Buffer.from(hash, 'hex')
    const b = Buffer.from(user.hash, 'hex')
    if (a.length !== b.length) return false
    return crypto.timingSafeEqual(a, b)
}

// ---------------------------------------------------------------- Token

function base64url(input) {
    return Buffer.from(input).toString('base64url')
}

function signToken(username) {
    const exp = Date.now() + config.tokenTtlDays * 24 * 60 * 60 * 1000
    const payload = base64url(JSON.stringify({ u: username, exp }))
    const sig = crypto.createHmac('sha256', config.secret).update(payload).digest('base64url')
    return { token: `${payload}.${sig}`, exp }
}

function verifyToken(token) {
    if (!token) return null
    const index = token.lastIndexOf('.')
    if (index <= 0) return null
    const payload = token.slice(0, index)
    const sig = token.slice(index + 1)
    const expect = crypto.createHmac('sha256', config.secret).update(payload).digest('base64url')
    const a = Buffer.from(sig)
    const b = Buffer.from(expect)
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null
    try {
        const data = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'))
        if (!data.u || !data.exp || data.exp < Date.now()) return null
        return data.u
    } catch (e) {
        return null
    }
}

// ---------------------------------------------------------------- 快照

function userDir(username) {
    return path.join(dataDir, 'users', username)
}

function snapshotFile(username, ns) {
    return path.join(userDir(username), 'snapshots', `${ns}.bin`)
}

function manifestFile(username) {
    return path.join(userDir(username), 'manifest.json')
}

function readManifest(username) {
    const manifest = readJson(manifestFile(username), { version: 1, namespaces: {} })
    if (!manifest.namespaces) manifest.namespaces = {}
    return manifest
}

function sha256(buffer) {
    return crypto.createHash('sha256').update(buffer).digest('hex')
}

function putSnapshot(username, ns, buffer, deviceId) {
    const target = snapshotFile(username, ns)
    mkdirp(path.dirname(target))
    if (fs.existsSync(target) && config.historyKeep > 0) {
        const historyDir = path.join(userDir(username), 'history')
        mkdirp(historyDir)
        const stamp = Date.now()
        fs.copyFileSync(target, path.join(historyDir, `${ns}-${stamp}.bin`))
        trimHistory(historyDir, ns)
    }
    const tmp = `${target}.tmp`
    fs.writeFileSync(tmp, buffer)
    fs.renameSync(tmp, target)

    const manifest = readManifest(username)
    const entry = {
        updatedAt: Date.now(),
        hash: sha256(buffer),
        size: buffer.length,
        deviceId: String(deviceId || '').slice(0, 128)
    }
    manifest.namespaces[ns] = entry
    writeJson(manifestFile(username), manifest)
    return entry
}

function trimHistory(historyDir, ns) {
    const files = fs.readdirSync(historyDir)
        .filter(name => name.startsWith(`${ns}-`) && name.endsWith('.bin'))
        .sort()
    const remove = files.length - config.historyKeep
    for (let i = 0; i < remove; i++) {
        try {
            fs.unlinkSync(path.join(historyDir, files[i]))
        } catch (e) {
            log(`清理历史快照失败: ${e.message}`)
        }
    }
}

function deleteSnapshot(username, ns) {
    const target = snapshotFile(username, ns)
    if (fs.existsSync(target)) fs.unlinkSync(target)
    const manifest = readManifest(username)
    delete manifest.namespaces[ns]
    writeJson(manifestFile(username), manifest)
}

// ---------------------------------------------------------------- HTTP 工具

function sendJson(res, status, body) {
    const payload = Buffer.from(JSON.stringify(body), 'utf8')
    res.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': payload.length,
        'Cache-Control': 'no-store'
    })
    res.end(payload)
}

function sendError(res, status, message) {
    sendJson(res, status, { error: message })
}

function readBody(req, limit) {
    return new Promise((resolve, reject) => {
        const chunks = []
        let size = 0
        req.on('data', chunk => {
            size += chunk.length
            if (size > limit) {
                reject(Object.assign(new Error('请求体超过大小限制'), { status: 413 }))
                req.destroy()
                return
            }
            chunks.push(chunk)
        })
        req.on('end', () => resolve(Buffer.concat(chunks)))
        req.on('error', reject)
    })
}

function readJsonBody(req) {
    return readBody(req, 256 * 1024).then(buffer => {
        if (buffer.length === 0) return {}
        return JSON.parse(buffer.toString('utf8'))
    })
}

function bearerToken(req) {
    const header = req.headers['authorization'] || ''
    const match = /^Bearer\s+(.+)$/i.exec(header.trim())
    return match ? match[1].trim() : ''
}

// 登录失败限流：同一来源 + 账号 60 秒内最多 8 次
const loginAttempts = new Map()

function tooManyAttempts(key) {
    const now = Date.now()
    const record = loginAttempts.get(key)
    if (!record || record.resetAt < now) {
        loginAttempts.set(key, { count: 1, resetAt: now + 60_000 })
        return false
    }
    record.count++
    return record.count > 8
}

// ---------------------------------------------------------------- 路由

function allowedNamespace(ns) {
    return config.namespaces.includes(ns)
}

async function handle(req, res, url) {
    const pathname = url.pathname.replace(/\/+$/, '') || '/'

    if (pathname === '/v1/health' && req.method === 'GET') {
        return sendJson(res, 200, {
            ok: true,
            service: 'legado-sync',
            namespaces: config.namespaces,
            allowRegister: config.allowRegister,
            time: Date.now()
        })
    }

    if (pathname === '/v1/auth/register' && req.method === 'POST') {
        if (!config.allowRegister) return sendError(res, 403, '服务端已关闭注册')
        const body = await readJsonBody(req)
        const username = normalizeUsername(body.username)
        const password = String(body.password || '')
        if (!username) return sendError(res, 400, '账号只能包含字母、数字、_ . @ -，长度 1-64')
        if (password.length < 6 || password.length > 128) {
            return sendError(res, 400, '密码长度需在 6-128 之间')
        }
        const created = createUser(username, password)
        if (created.error) return sendError(res, 409, created.error)
        log(`注册账号 ${username}`)
        const signed = signToken(username)
        return sendJson(res, 200, {
            username,
            token: signed.token,
            expiresAt: signed.exp
        })
    }

    if (pathname === '/v1/auth/login' && req.method === 'POST') {
        const body = await readJsonBody(req)
        const username = normalizeUsername(body.username)
        const password = String(body.password || '')
        if (!username || !password) return sendError(res, 400, '账号或密码为空')
        const ip = req.socket.remoteAddress || 'unknown'
        if (tooManyAttempts(`${ip}|${username}`)) {
            return sendError(res, 429, '尝试次数过多，请稍后再试')
        }
        if (!verifyUser(username, password)) return sendError(res, 401, '账号或密码错误')
        loginAttempts.delete(`${ip}|${username}`)
        const signed = signToken(username)
        return sendJson(res, 200, {
            username,
            token: signed.token,
            expiresAt: signed.exp
        })
    }

    // 以下接口都需要携带 token
    const username = verifyToken(bearerToken(req))
    if (!username) return sendError(res, 401, '登录状态无效或已过期，请重新登录')

    if (pathname === '/v1/manifest' && req.method === 'GET') {
        const manifest = readManifest(username)
        return sendJson(res, 200, {
            username,
            namespaces: manifest.namespaces,
            time: Date.now()
        })
    }

    const snapshotMatch = /^\/v1\/snapshot\/([A-Za-z0-9_-]+)$/.exec(pathname)
    if (snapshotMatch) {
        const ns = snapshotMatch[1]
        if (!allowedNamespace(ns)) return sendError(res, 400, `不支持的同步类型 ${ns}`)

        if (req.method === 'GET') {
            const file = snapshotFile(username, ns)
            if (!fs.existsSync(file)) return sendError(res, 404, '该类型还没有同步数据')
            const buffer = fs.readFileSync(file)
            const entry = readManifest(username).namespaces[ns] || {}
            res.writeHead(200, {
                'Content-Type': 'application/octet-stream',
                'Content-Length': buffer.length,
                'Cache-Control': 'no-store',
                'X-Sync-Namespace': ns,
                'X-Sync-Updated-At': String(entry.updatedAt || 0),
                'X-Sync-Hash': entry.hash || sha256(buffer),
                'X-Sync-Device': encodeURIComponent(entry.deviceId || '')
            })
            return res.end(buffer)
        }

        if (req.method === 'PUT') {
            const buffer = await readBody(req, config.maxSnapshotBytes)
            if (buffer.length === 0) return sendError(res, 400, '快照内容为空')
            const deviceId = req.headers['x-sync-device']
                ? decodeURIComponent(String(req.headers['x-sync-device']))
                : ''
            const entry = putSnapshot(username, ns, buffer, deviceId)
            log(`${username} 上传 ${ns} ${entry.size} 字节`)
            return sendJson(res, 200, Object.assign({ namespace: ns }, entry))
        }

        if (req.method === 'DELETE') {
            deleteSnapshot(username, ns)
            log(`${username} 删除 ${ns}`)
            return sendJson(res, 200, { namespace: ns, deleted: true })
        }

        return sendError(res, 405, '不支持的请求方法')
    }

    return sendError(res, 404, '接口不存在')
}

function requestHandler(req, res) {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`)
    handle(req, res, url).catch(error => {
        const status = error && error.status ? error.status : 500
        if (status >= 500) log(`请求处理失败 ${url.pathname}: ${error.stack || error.message}`)
        if (!res.headersSent) {
            sendError(res, status, error.message || '服务端异常')
        } else {
            res.end()
        }
    })
}

/** 配置了证书就用 HTTPS，否则 HTTP */
function createServerInstance() {
    if (!config.tlsCert && !config.tlsKey) {
        return { server: http.createServer(requestHandler), scheme: 'http' }
    }
    if (!config.tlsCert || !config.tlsKey) {
        throw new Error('tlsCert 与 tlsKey 需要同时配置')
    }
    const certPath = path.resolve(ROOT, config.tlsCert)
    const keyPath = path.resolve(ROOT, config.tlsKey)
    if (!fs.existsSync(certPath) || !fs.existsSync(keyPath)) {
        throw new Error(`TLS 证书或私钥不存在: ${certPath} / ${keyPath}`)
    }
    return {
        server: https.createServer({
            cert: fs.readFileSync(certPath),
            key: fs.readFileSync(keyPath)
        }, requestHandler),
        scheme: 'https'
    }
}

function main() {
    config = loadConfig()
    dataDir = path.resolve(ROOT, config.dataDir)
    mkdirp(dataDir)
    usersFile = path.join(dataDir, 'users.json')
    if (!fs.existsSync(usersFile)) saveUsers({ version: 1, users: {} })

    let instance
    try {
        instance = createServerInstance()
    } catch (error) {
        console.error(`启动失败: ${error.message}`)
        process.exit(1)
    }
    instance.server.listen(config.port, config.host, () => {
        log(`同步服务已启动: ${instance.scheme}://${config.host}:${config.port}`)
        log(`数据目录: ${dataDir}`)
        log(`同步类型: ${config.namespaces.join(', ')}`)
        log(`注册开关: ${config.allowRegister ? '开启' : '关闭'}`)
        if (instance.scheme === 'https') log(`自签证书: ${config.tlsCert}`)
    })
}

main()
