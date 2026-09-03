import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue({
    template: {
      compilerOptions: {
        isCustomElement: tag => tag === 'perspective-viewer',
      },
    },
  })],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/office': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/office/, ''),
      },
    },
  },
})
