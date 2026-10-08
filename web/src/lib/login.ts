export type LoginResult =
  | { kind: 'success' }
  | { kind: 'invalid' }
  | { kind: 'rateLimited' }
  | { kind: 'unexpected' }
  | { kind: 'unavailable' }

export function validatePin(pin: string): boolean {
  return /^\d{6}$/.test(pin)
}

export function mapLoginResponse(status: number): LoginResult {
  if (status === 200) return { kind: 'success' }
  if (status === 401) return { kind: 'invalid' }
  if (status === 429) return { kind: 'rateLimited' }
  return { kind: 'unexpected' }
}

export async function submitPin(pin: string): Promise<LoginResult> {
  try {
    const response = await fetch('/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ pin }),
      credentials: 'same-origin',
    })
    return mapLoginResponse(response.status)
  } catch {
    return { kind: 'unavailable' }
  }
}
