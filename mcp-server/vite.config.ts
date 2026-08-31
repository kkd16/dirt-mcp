import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    outDir: 'dist/public',
    rolldownOptions: {
      input: {
        app: 'src/web/client.ts',
        styles: 'src/web/app.css',
      },
      output: {
        entryFileNames: 'assets/[name].js',
        assetFileNames: (asset) =>
          asset.names.some((name) => name.endsWith('.css')) ? 'assets/app.css' : 'assets/[name]-[hash][extname]',
      },
    },
  },
});
