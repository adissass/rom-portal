import { expect, it, vi, afterEach } from 'vitest'
import { childPath, formatSize, listFolder } from './files'
afterEach(() => vi.unstubAllGlobals())
it('retains Unicode names and rejects traversal segments', () => {
  expect(childPath('ROMs', 'ゲーム')).toBe('ROMs/ゲーム')
  for (const name of ['..', '.', '/tmp', 'a/b', 'a\\b']) expect(() => childPath('', name)).toThrow()
})
it('distinguishes zero from unavailable received sizes', () => {
  expect(formatSize(0)).toBe('0 B')
  expect(formatSize(4096)).toBe('4 KB')
  for (const size of [null, undefined, -1, NaN]) expect(formatSize(size)).toBe('Size unavailable')
})
it('returns protected 401 without retaining listing content', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
  expect(await listFolder('Saves')).toEqual({ status: 401, entries: [], rootName: '' })
})
