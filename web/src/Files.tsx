import { useEffect, useRef, useState } from 'react'
import Login from './App'
import { Button } from './components/ui/button'
import { childPath, formatSize, listFolder } from './lib/files'
import type { Entry } from './lib/files'
import symbolLight from './assets/icons/symbol-web-light.svg'
import symbolDark from './assets/icons/symbol-web-dark.svg'
import folderIcon from './assets/icons/folder.svg'
import fileIcon from './assets/icons/file.svg'

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
        setRows([]); setRootName('Selected folder'); setState('login'); return
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

  useEffect(() => { if (state === 'ready' || state === 'lost' || state === 'error') heading.current?.focus() }, [state])

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
      <div className="files-toolbar"><Button variant="outline" disabled={disabled || !path} onClick={() => navigate(segments.slice(0, -1).join('/'))}>Back</Button><Button variant="outline" disabled={disabled} onClick={() => setAttempt(value => value + 1)}>Refresh</Button></div>
      <div aria-live="polite">{notice && <p>{notice}</p>}{state === 'loading' && <p className="empty-state">Loading folder…</p>}</div>
      {state === 'lost' && <div className="connection-recovery"><div role="alert" className="connection-alert"><h2>Connection lost</h2><p>Check that the host is running and both devices are on the same local network.</p></div><Button variant="outline" className="connection-retry" onClick={() => setAttempt(value => value + 1)}>Try connection again</Button></div>}
      {state === 'error' && <div role="alert"><p>Couldn’t load this folder.</p><Button onClick={() => setAttempt(value => value + 1)}>Try connection again</Button></div>}
      {state !== 'loading' && rows.length > 0 && <table className={`file-table${state === 'lost' ? ' disconnected-table' : ''}`}><thead><tr><th>Name</th><th>Size</th></tr></thead><tbody>{rows.map(row => <tr key={row.name}><td><div className="file-name"><img alt="" className="file-icon" src={row.isDirectory ? folderIcon : fileIcon}/>{row.isDirectory ? <button disabled={disabled} onClick={() => navigate(childPath(path, row.name))}>{row.name}</button> : <span>{row.name}</span>}</div></td><td title={row.sizeBytes === undefined ? undefined : `${row.sizeBytes} bytes; 1 KB = 1024 bytes`}>{row.isDirectory ? '' : formatSize(row.sizeBytes)}</td></tr>)}</tbody></table>}
      {state === 'ready' && rows.length === 0 && <p className="empty-state">This folder is empty.</p>}
      <p className="network-help files-footer">Showing only the selected folder.{state === 'lost' && ' Last loaded view; actions unavailable.'}</p>
    </section>
  </main>
}
