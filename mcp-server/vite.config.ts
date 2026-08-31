import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    outDir: 'dist/public',
    rolldownOptions: {
      input: 'src/web/client.ts',
      output: {
        entryFileNames: 'app.js',
      },
    },
  },
});
