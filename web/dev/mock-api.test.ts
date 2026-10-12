import { describe, expect, it } from 'vitest'
import { normalizedPath } from './mock-api'

describe('mock list path normalization', () => {
  it('rejects absolute paths after trimming whitespace', () => {
    expect(normalizedPath('/ROMs')).toBeNull()
    expect(normalizedPath('  /ROMs')).toBeNull()
  })

  it('keeps production-compatible relative paths', () => {
    expect(normalizedPath(' ROMs//Handheld ')).toBe('ROMs/Handheld')
    expect(normalizedPath('ROMs/../BIOS')).toBeNull()
  })
})
