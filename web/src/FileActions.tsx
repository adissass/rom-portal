import { useRef, useState } from 'react'
import { Dialog, DropdownMenu } from 'radix-ui'
import { Button } from './components/ui/button'
import { Input } from './components/ui/input'
import { childPath } from './lib/files'
import { mutate, proposedName } from './lib/actions'
import type { Operation } from './lib/actions'
import type { Entry } from './lib/files'

export type ActionTarget = { operation: Operation; parent: string; entry?: Entry; trigger: HTMLElement }

export function RowMenu({ entry, disabled, onAction, onDownload }: { entry: Entry; disabled: boolean; onAction: (operation: Operation, trigger: HTMLElement) => void; onDownload: () => void }) {
  const trigger = useRef<HTMLButtonElement>(null)
  return <DropdownMenu.Root><DropdownMenu.Trigger asChild><button ref={trigger} className="row-menu-trigger" disabled={disabled} aria-label={`Actions for ${entry.name}`}>•••</button></DropdownMenu.Trigger><DropdownMenu.Portal><DropdownMenu.Content className="row-menu" sideOffset={4} align="end" collisionPadding={16}>
    <DropdownMenu.Group>
      {!entry.isDirectory && <DropdownMenu.Item onSelect={onDownload}>Download</DropdownMenu.Item>}
      <DropdownMenu.Item onSelect={() => onAction('rename', trigger.current!)}>Rename</DropdownMenu.Item>
      <DropdownMenu.Item className="danger-text" onSelect={() => onAction('delete', trigger.current!)}>Delete</DropdownMenu.Item>
    </DropdownMenu.Group>
  </DropdownMenu.Content></DropdownMenu.Portal></DropdownMenu.Root>
}

export function ActionDialog({ target, onClose, onExpired, onComplete }: { target: ActionTarget; onClose: () => void; onExpired: () => void; onComplete: (name?: string) => void }) {
  const [value, setValue] = useState(target.entry?.name ?? '')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [uncertain, setUncertain] = useState(false)
  const lock = useRef(false)
  const input = useRef<HTMLInputElement>(null)
  const cancel = useRef<HTMLButtonElement>(null)
  const deleting = target.operation === 'delete'
  const creating = target.operation === 'mkdir'

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (lock.current || uncertain) return
    const name = deleting ? undefined : proposedName(value)
    if (name === null) { setError('Enter a name without / or \\. The names . and .. are not allowed.'); input.current?.focus(); return }
    if (!creating && !deleting && name === target.entry?.name) { onClose(); return }
    const path = creating ? childPath(target.parent, name!) : childPath(target.parent, target.entry!.name)
    // shortcut: backend trims boundary path whitespace; reject ambiguous existing targets until backend preserves them.
    if (path !== path.trim()) { setError('This existing name has boundary spaces and cannot be safely changed by this server.'); return }
    lock.current = true; setPending(true); setError('')
    const status = await mutate(target.operation, path, creating || deleting ? undefined : name!)
    lock.current = false; setPending(false)
    if (status === 401) { onExpired(); return }
    if (status >= 200 && status < 300) { onComplete(name ?? undefined); return }
    if (status === 0 || status >= 500) {
      setUncertain(true)
      setError('The change was not confirmed. Close and refresh the folder before deciding whether to try again.')
      return
    }
    setError(status === 409 ? creating ? 'A file already uses this name. Choose another name.' : 'That name is already in this folder. Choose another name.' : status === 400 ? 'Enter a name without / or \\. The names . and .. are not allowed.' : 'This item is no longer available. Close and refresh the folder.')
    input.current?.focus()
  }

  return <Dialog.Root open onOpenChange={open => { if (!open && !lock.current) onClose() }}><Dialog.Portal><Dialog.Overlay className="action-overlay"/><Dialog.Content className="action-dialog" onEscapeKeyDown={event => { if (lock.current) event.preventDefault() }} onPointerDownOutside={event => event.preventDefault()} onOpenAutoFocus={event => {
    event.preventDefault()
    if (deleting) cancel.current?.focus()
    else { input.current?.focus(); const dot = value.lastIndexOf('.'); input.current?.setSelectionRange(0, !target.entry?.isDirectory && dot > 0 ? dot : value.length) }
  }} onCloseAutoFocus={event => { event.preventDefault(); target.trigger.focus() }}>
    <Dialog.Title>{creating ? 'New folder' : deleting ? `Delete ${target.entry?.name}?` : 'Rename'}</Dialog.Title>
    <Dialog.Description>{deleting ? target.entry?.isDirectory ? 'This permanently deletes the folder and its contents. There is no undo.' : 'This permanently deletes the file. There is no undo.' : creating ? 'Create a folder in the current directory.' : target.entry?.name}</Dialog.Description>
    <form onSubmit={submit}>
      {!deleting && <><label htmlFor="action-name">Name</label><Input ref={input} id="action-name" value={value} onChange={event => { setValue(event.target.value); setError('') }} disabled={pending || uncertain} aria-invalid={!!error || undefined} aria-describedby={error ? 'action-error' : undefined}/></>}
      <p id="action-error" role="status" className="action-error">{error}</p>
      <div className="dialog-buttons"><Button ref={cancel} type="button" variant="outline" disabled={pending} onClick={() => uncertain ? onComplete() : onClose()}>{uncertain ? 'Close and refresh' : 'Cancel'}</Button><Button type="submit" variant={deleting ? 'destructive' : 'default'} disabled={pending || uncertain}>{pending ? deleting ? 'Deleting…' : creating ? 'Creating…' : 'Saving…' : deleting ? 'Delete' : creating ? 'Create folder' : 'Save'}</Button></div>
    </form>
  </Dialog.Content></Dialog.Portal></Dialog.Root>
}
