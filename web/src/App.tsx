import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import symbolInk from './assets/icons/symbol-ink.svg'
import symbolWhite from './assets/icons/symbol-white.svg'
import { Button } from './components/ui/button'
import { Input } from './components/ui/input'
import { submitPin, validatePin } from './lib/login'

type Message = 'validation' | 'invalid' | 'rateLimited' | 'unavailable' | null

const messages: Record<Exclude<Message, null>, string> = {
  validation: 'Enter a six-digit PIN.',
  invalid: 'That PIN doesn’t match. Check your handheld and try again.',
  rateLimited: 'Too many attempts. Wait before trying again.',
  unavailable: 'Host unavailable. Check RomPortal on your handheld, then try again.',
}

function App() {
  const [pin, setPin] = useState('')
  const [revealed, setRevealed] = useState(false)
  const [pending, setPending] = useState(false)
  const [coolingDown, setCoolingDown] = useState(false)
  const [message, setMessage] = useState<Message>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (message === 'validation' || message === 'invalid') inputRef.current?.focus()
  }, [message])

  useEffect(() => {
    if (!coolingDown) return
    const timer = window.setTimeout(() => {
      setCoolingDown(false)
      setMessage(null)
    }, 30_000)
    return () => window.clearTimeout(timer)
  }, [coolingDown])

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending || coolingDown) return
    if (!validatePin(pin)) {
      setMessage('validation')
      return
    }

    setPending(true)
    setMessage(null)
    const result = await submitPin(pin)
    if (result.kind === 'success') {
      window.location.assign('/')
      return
    }

    setPending(false)
    if (result.kind === 'rateLimited') {
      setCoolingDown(true)
      setMessage('rateLimited')
      return
    }
    setMessage(result.kind === 'invalid' ? 'invalid' : 'unavailable')
  }

  const disabled = pending || coolingDown
  const hasError = message !== null

  return (
    <main className="login-page">
      <header className="site-header">
        <div className="brand">
          <picture>
            <source media="(prefers-color-scheme: dark)" srcSet={symbolWhite} />
            <img src={symbolInk} alt="" className="brand-symbol" />
          </picture>
          <span>Browser</span>
        </div>
        <span className="network-label">Local network</span>
      </header>

      <section className="login-panel" aria-labelledby="login-title">
        <h1 id="login-title">Open your folder.</h1>
        <p className="login-help">Enter the PIN shown in RomPortal on your handheld.</p>
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
              onChange={(event) => setPin(event.target.value.replace(/\D/g, '').slice(0, 6))}
              disabled={disabled}
              aria-invalid={hasError || undefined}
              aria-describedby={message ? 'pin-message' : undefined}
            />
            <Button
              type="button"
              className="secondary-button reveal-button"
              disabled={disabled}
              onClick={() => setRevealed((value) => !value)}
            >
              {revealed ? 'Hide' : 'Show'}
            </Button>
          </div>

          <p id="pin-message" className="form-message" aria-live="polite">
            {message ? messages[message] : ''}
          </p>

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
