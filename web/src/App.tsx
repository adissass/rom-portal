import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import symbolWebLight from './assets/icons/symbol-web-light.svg'
import symbolWebDark from './assets/icons/symbol-web-dark.svg'
import { Button } from './components/ui/button'
import { Input } from './components/ui/input'
import { submitPin, validatePin } from './lib/login'

type Message = 'validation' | 'invalid' | 'rateLimited' | 'unavailable' | null

const messages: Record<Exclude<Message, null>, string> = {
  validation: 'Enter a six-digit PIN.',
  invalid: 'That PIN doesn’t match. Check your handheld and try again.',
  rateLimited: 'Too many attempts. Wait before trying again.',
  unavailable: 'Host unavailable.',
}

const unavailableHelp = 'Check RomPortal on your handheld. If it stopped, start it again and use the new address and PIN.'

function App({ onSignedIn, expired = false }: { onSignedIn?: () => void; expired?: boolean }) {
  const [pin, setPin] = useState('')
  const [revealed, setRevealed] = useState(false)
  const [pending, setPending] = useState(false)
  const [coolingDown, setCoolingDown] = useState(false)
  const [cooldownMs, setCooldownMs] = useState(30_000)
  const [message, setMessage] = useState<Message>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (expired) inputRef.current?.focus()
  }, [expired])

  useEffect(() => {
    if (message !== 'validation' && message !== 'invalid') return
    inputRef.current?.focus()
    if (message === 'invalid') inputRef.current?.select()
  }, [message])

  useEffect(() => {
    if (!coolingDown) return
    const timer = window.setTimeout(() => {
      setCoolingDown(false)
      setMessage(null)
    }, cooldownMs)
    return () => window.clearTimeout(timer)
  }, [cooldownMs, coolingDown])

  function updatePin(value: string) {
    setPin(value)
    if (!coolingDown) setMessage(null)
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending || coolingDown) return
    const submittedPin = pin.trim()
    if (!validatePin(submittedPin)) {
      setMessage('validation')
      return
    }

    setPending(true)
    setMessage(null)
    const result = await submitPin(submittedPin)
    if (result.kind === 'success') {
      setPin('')
      if (onSignedIn) { onSignedIn(); return }
      window.location.assign('/')
      return
    }

    setPending(false)
    if (result.kind === 'rateLimited') {
      setCooldownMs(result.retryAfterMs ?? 30_000)
      setCoolingDown(true)
      setMessage('rateLimited')
      return
    }
    setMessage(result.kind === 'invalid' ? 'invalid' : 'unavailable')
  }

  const disabled = pending || coolingDown
  const hasError = message === 'validation' || message === 'invalid'

  return (
    <main className="login-page">
      <header className="site-header">
        <div className="brand">
          <picture>
            <source media="(prefers-color-scheme: dark)" srcSet={symbolWebDark} />
            <img src={symbolWebLight} alt="" className="brand-symbol" />
          </picture>
          <span>Browser</span>
        </div>
      </header>

      <section className="login-panel" aria-labelledby="login-title">
        <h1 id="login-title">Open your folder.</h1>
        <p className="login-help">Enter the PIN shown in RomPortal on your handheld.</p>
        {expired && <p id="session-expired" role="status">Enter the PIN again to continue.</p>}
        <p className="host-address">{window.location.host}</p>

        <form onSubmit={handleSubmit} noValidate>
          <label htmlFor="pin">PIN</label>
          <div className="pin-row">
            <Input
              ref={inputRef}
              id="pin"
              name="pin"
              type={revealed ? 'text' : 'password'}
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9]*"
              maxLength={6}
              placeholder="6-digit PIN"
              value={pin}
              onChange={(event) => updatePin(event.target.value)}
              onPaste={(event) => {
                event.preventDefault()
                const selectionStart = event.currentTarget.selectionStart ?? pin.length
                const selectionEnd = event.currentTarget.selectionEnd ?? pin.length
                const pastedPin = event.clipboardData.getData('text').trim()
                updatePin(`${pin.slice(0, selectionStart)}${pastedPin}${pin.slice(selectionEnd)}`)
              }}
              className="login-input"
              disabled={disabled}
              aria-invalid={hasError || undefined}
              aria-describedby={[expired && 'session-expired', message && 'pin-message'].filter(Boolean).join(' ') || undefined}
            />
            <Button
              type="button"
              variant="outline"
              className="reveal-button"
              disabled={disabled}
              onClick={() => setRevealed((value) => !value)}
            >
              {revealed ? 'Hide' : 'Show'}
            </Button>
          </div>

          <div id="pin-message" className="form-message" aria-live="polite">
            {message && <p>{messages[message]}</p>}
            {message === 'unavailable' && <p>{unavailableHelp}</p>}
          </div>

          <Button type="submit" className="primary-button" disabled={disabled}>
            {pending ? 'Checking PIN…' : coolingDown ? 'Wait to try again' : 'Open files'}
          </Button>
        </form>

        <p className="network-help">Keep RomPortal running. Both devices must be on the same local network.</p>
      </section>
    </main>
  )
}

export default App
