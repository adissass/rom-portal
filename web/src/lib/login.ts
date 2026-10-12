export type LoginResult =
  | { kind: 'success' }
  | { kind: 'invalid' }
  | { kind: 'rateLimited'; retryAfterMs?: number }
  | { kind: 'unexpected' }
  | { kind: 'unavailable' }

export function validatePin(pin: string): boolean {
  return /^\d{6}$/.test(pin)
}

export function mapLoginResponse(status: number, retryAfterMs?: number): LoginResult {
  if (status === 200) return { kind: 'success' }
  if (status === 401) return { kind: 'invalid' }
  if (status === 429) return { kind: 'rateLimited', retryAfterMs }
  return { kind: 'unexpected' }
}

export function parseRetryAfter(value: string | null, now = Date.now()): number | undefined {
  if (value === null) return undefined

  const trimmedValue = value.trim()
  if (/^\d+(?:\.\d+)?$/.test(trimmedValue)) return Math.ceil(Number(trimmedValue) * 1_000)

  const timestamp = Date.parse(trimmedValue)
  return Number.isFinite(timestamp) && timestamp > now ? timestamp - now : undefined
}

export async function submitPin(pin: string): Promise<LoginResult> {
  try {
    const response = await fetch('/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ pin }),
      credentials: 'same-origin',
    })
    return mapLoginResponse(response.status, parseRetryAfter(response.headers.get('Retry-After')))
  } catch {
    return { kind: 'unavailable' }
  }
}
