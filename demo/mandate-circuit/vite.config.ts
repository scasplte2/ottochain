import motionCanvas from '@motion-canvas/vite-plugin';
export default {plugins: [motionCanvas.default({project: process.env.OTTO_FILM_PROJECT ?? './project.ts'})], server: {host: '127.0.0.1'}};
