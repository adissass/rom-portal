import type { ServerResponse } from 'node:http'
import type { Plugin } from 'vite'

type Entry = { name: string; isDirectory: boolean; sizeBytes: number }

// Match GET /api/list's production response shape. These are preview data, not files on disk.
const files: Record<string, Entry[]> = {
  '': [
    { name: 'BIOS', isDirectory: true, sizeBytes: 0 },
    { name: 'ROMs', isDirectory: true, sizeBytes: 0 },
    { name: 'Saves', isDirectory: true, sizeBytes: 0 },
    { name: 'README.txt', isDirectory: false, sizeBytes: 1842 },
  ],
  BIOS: [
    { name: 'system-bios.bin', isDirectory: false, sizeBytes: 524288 },
  ],
  ROMs: [
    { name: 'Empty Collection', isDirectory: true, sizeBytes: 0 },
    { name: 'Handheld', isDirectory: true, sizeBytes: 0 },
    { name: 'Sample Game With A Deliberately Long Filename (Europe).zip', isDirectory: false, sizeBytes: 7340032 },
  ],
  'ROMs/Empty Collection': [],
  'ROMs/Handheld': [
    { name: 'sample-game.gba', isDirectory: false, sizeBytes: 16777216 },
    { name: 'sample-large-image.iso', isDirectory: false, sizeBytes: 4294967296 },
  ],
  Saves: [
    { name: 'sample-game.sav', isDirectory: false, sizeBytes: 32768 },
  ],
}

function respond(res: ServerResponse, status: number, body: string, contentType: string) {
  res.writeHead(status, { 'Content-Type': contentType, 'Cache-Control': 'no-store' })
  res.end(body)
}

function isSignedIn(cookieHeader: string | undefined): boolean {
  return cookieHeader?.split(';').some((cookie) => cookie.trim() === 'rs_session=preview') ?? false
}

export function normalizedPath(path: string): string | null {
  const trimmed = path.trim()
  if (trimmed.startsWith('/')) return null
  const segments = trimmed.split('/').filter((segment) => segment.trim())
  if (segments.some((segment) => segment === '.' || segment === '..' || segment.includes('\\'))) return null
  return segments.join('/')
}

export function mockApi(): Plugin {
  return {
    name: 'romportal-dev-mock-api',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = new URL(req.url ?? '/', 'http://localhost')

        if (req.method === 'POST' && url.pathname === '/login') {
          let body = ''
          let tooLarge = false
          req.on('data', (chunk: Buffer) => {
            if (tooLarge) return
            body += chunk.toString()
            if (body.length > 1024) {
              tooLarge = true
              respond(res, 413, 'Request too large', 'text/plain')
            }
          })
          req.on('end', () => {
            if (tooLarge) return
            const pin = new URLSearchParams(body).get('pin')
            if (pin === '000000') {
              respond(res, 401, 'Invalid PIN', 'text/plain')
            } else if (pin === '999999') {
              res.setHeader('Retry-After', '30')
              respond(res, 429, 'Too many attempts', 'text/plain')
            } else if (pin === '888888') {
              respond(res, 503, 'Host unavailable', 'text/plain')
            } else if (pin && /^\d{6}$/.test(pin)) {
              res.setHeader('Set-Cookie', 'rs_session=preview; Path=/; HttpOnly; SameSite=Lax')
              respond(res, 200, 'Login successful', 'text/plain')
            } else {
              respond(res, 401, 'Invalid PIN', 'text/plain')
            }
          })
          return
        }

        if (req.method === 'GET' && url.pathname === '/') {
          if (!isSignedIn(req.headers.cookie)) {
            res.writeHead(302, { Location: '/web/' })
            res.end()
            return
          }
          respond(
            res,
            200,
            '<!doctype html><html lang="en"><meta charset="utf-8"><title>Preview signed in</title><h1>Preview signed in</h1><p>The React file manager is not built yet. The mock API is available at <a href="/api/list?path=">/api/list</a>.</p>',
            'text/html',
          )
          return
        }

        if (req.method === 'GET' && url.pathname === '/api/list') {
          if (!isSignedIn(req.headers.cookie)) {
            respond(res, 401, 'Unauthorized', 'text/plain')
            return
          }
          const path = normalizedPath(url.searchParams.get('path') ?? '')
          if (path === null) {
            respond(res, 400, JSON.stringify({ error: 'Invalid path' }), 'application/json')
          } else if (Object.hasOwn(files, path)) {
            respond(res, 200, JSON.stringify({ entries: files[path] }), 'application/json')
          } else {
            respond(res, 404, JSON.stringify({ error: 'Path not found' }), 'application/json')
          }
          return
        }

        next()
      })
    },
  }
}
