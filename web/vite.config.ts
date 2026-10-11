import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import { mockApi } from './dev/mock-api.js'

// https://vite.dev/config/
export default defineConfig(({ command, mode }) => ({
  base: '/web/',
  plugins: [
    react(),
    tailwindcss(),
    ...(command === 'serve' && mode === 'mock'
      ? [mockApi()]
      : []),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: '../app/src/main/assets/web',
    emptyOutDir: true,
  },
}))
