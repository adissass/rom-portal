// @vitest-environment jsdom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import type { Root } from 'react-dom/client'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import Files from './Files'

let host: HTMLDivElement
let root: Root

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true)
  history.replaceState(null, '', '/?path=ROMs')
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
})

afterEach(async () => {
  await act(async () => root.unmount())
  host.remove()
  vi.unstubAllGlobals()
})

function listing(name: string, isDirectory = false) {
  return new Response(JSON.stringify({ rootName: 'Emulator', entries: [{ name, isDirectory, sizeBytes: 0 }] }))
}

async function click(label: string) {
  const button = Array.from(host.querySelectorAll('button')).find(button => button.textContent === label)
  if (!button) throw new Error(`Missing button: ${label}`)
  await act(async () => button.click())
}

it('clears protected rows, focuses and describes the PIN, then restores the intended folder', async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(listing('protected.gba'))
    .mockResolvedValueOnce(new Response(null, { status: 401 }))
    .mockResolvedValueOnce(new Response(null, { status: 200 }))
    .mockResolvedValueOnce(listing('protected.gba'))
  vi.stubGlobal('fetch', fetch)
  await act(async () => root.render(<Files />))
  expect(host.textContent).toContain('protected.gba')
  await click('Refresh')
  expect(host.textContent).not.toContain('protected.gba')
  const pin = host.querySelector<HTMLInputElement>('#pin')!
  expect(document.activeElement).toBe(pin)
  expect(pin.getAttribute('aria-describedby')).toContain('session-expired')
  expect(host.querySelector('#session-expired')?.textContent).toBe('Enter the PIN again to continue.')
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(pin, '123456')
    pin.dispatchEvent(new Event('input', { bubbles: true }))
  })
  await act(async () => host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })))
  expect(fetch.mock.calls[2][0]).toBe('/login')
  expect(fetch.mock.calls[3][0]).toBe('/api/list?path=ROMs')
  expect(host.textContent).toContain('protected.gba')
})

it('ignores a superseded response even if the server finishes after cancellation', async () => {
  let finishOld!: (response: Response) => void
  const fetch = vi.fn()
    .mockImplementationOnce(() => new Promise<Response>(resolve => { finishOld = resolve }))
    .mockResolvedValueOnce(listing('current.gba'))
  vi.stubGlobal('fetch', fetch)
  await act(async () => root.render(<Files />))
  await act(async () => {
    history.replaceState(null, '', '/?path=Saves')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })
  expect(fetch.mock.calls[0][1].signal.aborted).toBe(true)
  await act(async () => finishOld(listing('stale.gba')))
  expect(host.textContent).toContain('current.gba')
  expect(host.textContent).not.toContain('stale.gba')
})
