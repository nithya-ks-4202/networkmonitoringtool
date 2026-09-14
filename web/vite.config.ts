import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    // Proxied in development so the browser sees one origin and CORS never
    // enters the picture. In production the same is done by the reverse proxy
    // in front of both, which keeps the two environments behaving alike.
    proxy: {
      '/api': {
        target: process.env.NMS_SERVER_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        // Charting is heavy and only needed on pages that draw graphs;
        // splitting it keeps the initial load small for the problem view,
        // which is the page operators open first and most often.
        manualChunks: {
          charts: ['recharts'],
          vendor: ['react', 'react-dom', 'react-router-dom'],
        },
      },
    },
  },
})
