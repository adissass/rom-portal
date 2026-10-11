import { afterEach, describe, expect, it, vi } from 'vitest'
import { mapLoginResponse, parseRetryAfter, submitPin, validatePin } from './login'

afterEach(() => vi.unstubAllGlobals())

describe('validatePin', () => {
  it('accepts six digits including leading zeroes', () => {
    expect(validatePin('001234')).toBe(true)
  })

  it('rejects empty, nonnumeric, and wrong-length values', () => {
    expect(validatePin('')).toBe(false)
    expect(validatePin('12a456')).toBe(false)
    expect(validatePin('12345')).toBe(false)
    expect(validatePin('1234567')).toBe(false)
  })
})

describe('mapLoginResponse', () => {
  it.each([
    [200, 'success'],
    [401, 'invalid'],
    [429, 'rateLimited'],
    [500, 'unexpected'],
  ] as const)('maps %i to %s', (status, kind) => {
    expect(mapLoginResponse(status).kind).toBe(kind)
  })
})

describe('parseRetryAfter', () => {
  it('uses valid seconds or dates and rejects malformed values', () => {
    expect(parseRetryAfter('1.5', 0)).toBe(1_500)
    expect(parseRetryAfter('Thu, 01 Jan 1970 00:00:02 GMT', 0)).toBe(2_000)
    expect(parseRetryAfter('', 0)).toBeUndefined()
  })
})

it('maps a network failure to unavailable', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
  await expect(submitPin('001234')).resolves.toEqual({ kind: 'unavailable' })
})

it('uses Retry-After for a rate-limited login', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, {
    status: 429,
    headers: { 'Retry-After': '2' },
  })))

  await expect(submitPin('001234')).resolves.toEqual({ kind: 'rateLimited', retryAfterMs: 2_000 })
})
