export type Operation = 'mkdir' | 'rename' | 'delete'

export function proposedName(value: string): string | null {
  const name = value.trim()
  return !name || name === '.' || name === '..' || /[/\\]/.test(name) ? null : name
}

export async function mutate(operation: Operation, path: string, newName?: string) {
  try {
    const body = new URLSearchParams({ path })
    if (newName !== undefined) body.set('newName', newName)
    const response = await fetch(`/api/${operation}`, { method: 'POST', body, credentials: 'same-origin' })
    return response.status
  } catch {
    return 0
  }
}
