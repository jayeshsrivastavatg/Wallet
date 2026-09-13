import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/wallets': 'http://localhost:8080',
      '/transfers': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
})
