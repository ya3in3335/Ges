// Renders the client's animation (branding/client-animation-src/oran-motion.html) to an MP4 for
// the Android intro, frame by frame with the client's own deterministic render(ctx, W, H, t) —
// the same approach as the client's tools/3_render_video.py. Nothing in the motion is changed.
//
// Usage: NODE_PATH=$(npm root -g) node tools/render_intro_video.js [width=720] [height=1560] [fps=60]
// Needs: playwright (Chromium) and ffmpeg.
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const path = require('path');

const W = parseInt(process.argv[2] || '720', 10);  // 720x1560@60 stays within H.264 level 4.2 (broad device support)
const H = parseInt(process.argv[3] || '1560', 10);
const FPS = parseInt(process.argv[4] || '60', 10);
const DUR = 9.0;          // client timeline length (DUR in the client's code)
const HOLD = 0.5;         // keep the final frame briefly before the app appears
const root = path.resolve(__dirname, '..');
const src = 'file://' + path.join(root, 'branding/client-animation-src/oran-motion.html');
const out = path.join(root, 'android-app/app/src/main/res/raw/intro.mp4');

(async () => {
  const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-i', '-',
    '-c:v', 'libx264', '-profile:v', 'main', '-level', '4.2', '-preset', 'slow', '-crf', '22', '-pix_fmt', 'yuv420p',
    '-movflags', '+faststart', '-an', out], { stdio: ['pipe', 'inherit', 'inherit'] });
  const browser = await chromium.launch({ args: ['--use-gl=swiftshader'] });
  const page = await browser.newPage({ viewport: { width: 800, height: 600 } });
  await page.goto(src);
  await page.evaluate('window.__ready');
  const frames = Math.round((DUR + HOLD) * FPS);
  for (let i = 0; i < frames; i++) {
    const t = Math.min(i / FPS, DUR);
    const data = await page.evaluate(`window.__render(${W},${H},${t}).toDataURL("image/jpeg",0.95)`);
    if (!ff.stdin.write(Buffer.from(data.split(',')[1], 'base64'))) await new Promise((r) => ff.stdin.once('drain', r));
  }
  await browser.close();
  ff.stdin.end();
  ff.on('close', (code) => { console.log(code === 0 ? `wrote ${out} (${frames} frames)` : `ffmpeg failed ${code}`); process.exit(code); });
})();
