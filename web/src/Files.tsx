import { useEffect, useRef, useState } from 'react'
import Login from './App'
import { Button } from './components/ui/button'
import { childPath, formatSize, listFolder } from './lib/files'
import type { Entry } from './lib/files'
import symbolLight from './assets/icons/symbol-web-light.svg'
import symbolDark from './assets/icons/symbol-web-dark.svg'
import folderIcon from './assets/icons/folder.svg'
import fileIcon from './assets/icons/file.svg'
import { ActionDialog, RowMenu } from './FileActions'
import type { ActionTarget } from './FileActions'

export default function Files() {
  const [path, setPath] = useState(() => new URLSearchParams(location.search).get('path') ?? '')
  const [rows, setRows] = useState<Entry[]>([])
  const [rootName, setRootName] = useState('Selected folder')
  const [loadedPath, setLoadedPath] = useState('')
  const [state, setState] = useState<'loading' | 'ready' | 'lost' | 'login' | 'error'>('loading')
  const [notice, setNotice] = useState('')
  const [attempt, setAttempt] = useState(0)
  const heading = useRef<HTMLHeadingElement>(null)
  const authenticated = useRef(false)
  const [action, setAction] = useState<ActionTarget | null>(null)
  const [download, setDownload] = useState('')
  const focusName = useRef<string | undefined>(undefined)

  function expire() {
    setAction(null); setDownload(''); setRows([]); setRootName('Selected folder'); setState('login')
  }

  function completed(name?: string) {
    const index = rows.findIndex(row => row.name === action?.entry?.name)
    focusName.current = action?.operation === 'delete' ? rows[index + 1]?.name ?? rows[index - 1]?.name : name
    setNotice(action?.operation === 'mkdir' && name ? 'Folder is ready.' : '')
    setAction(null)
    setAttempt(value => value + 1)
  }

  function navigate(next: string, replace = false) {
    const url = new URL(location.href)
    if (next) url.searchParams.set('path', next)
    else url.searchParams.delete('path')
    history[replace ? 'replaceState' : 'pushState'](null, '', url)
    setPath(next)
  }

  useEffect(() => {
    const back = () => setPath(new URLSearchParams(location.search).get('path') ?? '')
    window.addEventListener('popstate', back)
    return () => window.removeEventListener('popstate', back)
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    setState('loading')
    listFolder(path, controller.signal).then(result => {
      if (controller.signal.aborted) return
      if (result.status === 401) {
        expire(); return
      }
      if (result.status === 404 && path) {
        setRows([])
        setNotice('This folder is no longer available. Showing the selected folder.')
        navigate('', true); return
      }
      if (result.status !== 200) { setRows([]); setState('error'); return }
      authenticated.current = true
      setRows(result.entries); setLoadedPath(path); setRootName(result.rootName); setState('ready')
    }).catch(() => {
      if (!controller.signal.aborted) setState('lost')
    })
    return () => controller.abort()
  }, [path, attempt])

  useEffect(() => {
    if (state !== 'ready' && state !== 'lost' && state !== 'error') return
    const row = Array.from(document.querySelectorAll<HTMLElement>('[data-entry]')).find(row => row.dataset.entry === focusName.current)
    if (state === 'ready' && row) row.focus()
    else heading.current?.focus()
    focusName.current = undefined
  }, [state])

  if (state === 'login') return <Login expired={authenticated.current} onSignedIn={() => setAttempt(value => value + 1)} />
  const disabled = state !== 'ready'
  const segments = (state === 'lost' ? loadedPath : path).split('/').filter(Boolean)
  return <main className="login-page files-page">
    <header className="site-header"><div className="brand"><picture><source media="(prefers-color-scheme: dark)" srcSet={symbolDark}/><img src={symbolLight} alt="" className="brand-symbol"/></picture><span>Browser</span></div></header>
    <section className="files-panel">
      <div className="files-heading"><h1 ref={heading} tabIndex={-1}>Files</h1>{state === 'lost' && <span className="connection-status"><span aria-hidden="true">●</span> Disconnected</span>}</div>
      <nav aria-label="Folder breadcrumbs" className="breadcrumbs">
        <button disabled={disabled || !path} onClick={() => navigate('')} aria-current={!path ? 'page' : undefined}>{rootName}</button>
        {segments.map((segment, index) => <span key={index}> / <button disabled={disabled || index === segments.length - 1} aria-current={index === segments.length - 1 ? 'page' : undefined} onClick={() => navigate(segments.slice(0, index + 1).join('/'))}>{segment}</button></span>)}
      </nav>
      <div className="files-toolbar"><Button variant="outline" disabled={disabled || !path} onClick={() => navigate(segments.slice(0, -1).join('/'))}>Back</Button><Button variant="outline" disabled={disabled} onClick={() => setAttempt(value => value + 1)}>Refresh</Button><Button variant="outline" disabled={disabled} onClick={event => setAction({ operation: 'mkdir', parent: path, trigger: event.currentTarget })}>New folder</Button></div>
      <div aria-live="polite">{notice && <p>{notice}</p>}{state === 'loading' && <p className="empty-state">Loading folder…</p>}</div>
      {state === 'lost' && <div className="connection-recovery"><div role="alert" className="connection-alert"><h2>Connection lost</h2><p>Check that the host is running and both devices are on the same local network.</p></div><Button variant="outline" className="connection-retry" onClick={() => setAttempt(value => value + 1)}>Try connection again</Button></div>}
      {state === 'error' && <div role="alert"><p>Couldn’t load this folder.</p><Button onClick={() => setAttempt(value => value + 1)}>Try connection again</Button></div>}
      {state !== 'loading' && rows.length > 0 && <table className={`file-table${state === 'lost' ? ' disconnected-table' : ''}`}><thead><tr><th>Name</th><th>Size</th><th className="actions-column"><span className="sr-only">Actions</span></th></tr></thead><tbody>{rows.map(row => <tr key={row.name}><td><div className="file-name"><img alt="" className="file-icon" src={row.isDirectory ? folderIcon : fileIcon}/>{row.isDirectory ? <button data-entry={row.name} disabled={disabled} onClick={() => navigate(childPath(path, row.name))}>{row.name}</button> : <span data-entry={row.name} tabIndex={-1}>{row.name}</span>}</div></td><td title={row.sizeBytes === undefined ? undefined : `${row.sizeBytes} bytes; 1 KB = 1024 bytes`}>{row.isDirectory ? '' : formatSize(row.sizeBytes)}</td><td className="actions-column"><RowMenu entry={row} disabled={disabled} onAction={(operation, trigger) => setAction({ operation, parent: path, entry: row, trigger })} onDownload={() => {
        const target = childPath(path, row.name)
        if (target !== target.trim()) { setNotice('This existing name has boundary spaces and cannot be safely downloaded by this server.'); return }
        setDownload(`/api/download?${new URLSearchParams({ path: target })}`)
      }}/></td></tr>)}</tbody></table>}
      {state === 'ready' && rows.length === 0 && <div className="empty-state"><p>This folder is empty.</p><p className="network-help">Create a folder to get started.</p></div>}
      <p className="network-help files-footer">Showing only the selected folder.{state === 'lost' && ' Last loaded view; actions unavailable.'}</p>
    </section>
    {action && <ActionDialog target={action} onClose={() => setAction(null)} onExpired={expire} onComplete={completed}/>}
    {download && <iframe key={download} title="File download" hidden src={download} onLoad={event => {
      const document = event.currentTarget.contentDocument
      if (!document || document.location.pathname !== '/api/download') return
      const body = document.body?.textContent?.trim()
      if (body === 'Unauthorized') expire()
      else if (body) { setNotice('Couldn’t download this file. Check the connection and try again.'); setDownload('') }
    }}/>}
  </main>
}
