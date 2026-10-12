import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import Files from './Files.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Files />
  </StrictMode>,
)
