// Static build of the interactive board (board.html) for hosting, e.g. on ottochain.ai.
//   npx vite build --config vite.board.config.ts   →  dist/board/
// Needs output/film-mix.m4a (the narrated mix) to exist; see README.md.
import {defineConfig} from 'vite';
export default defineConfig({
  base: './',
  esbuild: {jsx: 'automatic', jsxImportSource: '@motion-canvas/2d/lib'},
  build: {outDir: 'dist/board', emptyOutDir: true, rollupOptions: {input: 'board.html'}, assetsInlineLimit: 0},
});
