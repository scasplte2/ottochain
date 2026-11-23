import motionCanvas from '@motion-canvas/vite-plugin';
export default {plugins: [motionCanvas.default({project: ['./project.ts', './project-vertical.ts']})], server: {host: '127.0.0.1'}};
