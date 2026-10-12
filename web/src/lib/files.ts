export type Entry = { name: string; isDirectory: boolean; sizeBytes?: number }

export function childPath(parent: string, name: string): string {
  if (!name || name === '.' || name === '..' || /[/\\]/.test(name)) throw new Error('Invalid folder name')
  return parent ? `${parent}/${name}` : name
}

export function formatSize(size: unknown): string {
  if (typeof size !== 'number' || !Number.isFinite(size) || size < 0) return 'Size unavailable'
  const unit = Math.min(3, Math.floor(Math.log(Math.max(1, size)) / Math.log(1024)))
  return `${unit ? Number((size / 1024 ** unit).toFixed(1)) : size} ${['B', 'KB', 'MB', 'GB'][unit]}`
}

export async function listFolder(path: string, signal?: AbortSignal) {
  const response = await fetch(`/api/list?${new URLSearchParams({ path })}`, { signal, credentials: 'same-origin' })
  if (!response.ok) return { status: response.status, entries: [] as Entry[], rootName: '' }
  const data = await response.json()
  if (!Array.isArray(data.entries)) throw new Error('Invalid listing')
  return { status: 200, entries: data.entries as Entry[], rootName: typeof data.rootName === 'string' ? data.rootName : 'Selected folder' }
}
