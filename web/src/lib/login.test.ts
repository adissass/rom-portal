import { afterEach, describe, expect, it, vi } from 'vitest'
import { mapLoginResponse, submitPin, validatePin } from './login'

afterEach(() => vi.unstubAllGlobals())

describe('validatePin', () => {
  it('accepts six digits including leading zeroes', () => {
    expect(validatePin('001234')).toBe(true)
  })

  it('rejects empty, nonnumeric, and wrong-length values', () => {
    expect(validatePin('')).toBe(false)
    expect(validatePin('12a456')).toBe(false)
    expect(validatePin('12345')).toBe(false)
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

it('maps a network failure to unavailable', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
  await expect(submitPin('001234')).resolves.toEqual({ kind: 'unavailable' })
})
