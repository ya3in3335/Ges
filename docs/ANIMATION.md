# Client intro animation — integration notes

**Supplied:** `oran-motion-src.zip` (kept unmodified in `branding/client-animation-src/`):
`oran-motion.html` (canvas + JavaScript player with embedded layers), 12 PNG layers, timeline
template and the client's own export scripts (including `tools/3_render_video.py`).

**Integration:** the motion is a deterministic `render(ctx, W, H, t)` (9.0 s). It is rendered frame
by frame, with the client's own code and no changes, into `android-app/app/src/main/res/raw/intro.mp4`
by `tools/render_intro_video.js` — the same method as the client's `3_render_video.py` — at a
tall phone ratio (720×1560, 60 fps, H.264 Main@4.2 for broad device support, ~1 MB), plus a 0.5 s
hold on the final frame.

The app plays it natively with Media3 (`ui/intro/IntroScreen.kt`): no WebView and no JavaScript on
the phone. `RESIZE_MODE_ZOOM` fills any screen; the logo is centred with side margins, so other
aspect ratios only trim empty background. Skip button after 1.5 s, back press skips, any playback
error or a 14 s safety timeout continues to the app. Plays on cold start only; can be disabled in
Settings; not shown when opened from a notification.

The system starting window is plain black (no icon on Android 12+) to blend into the first frame;
the animation ends on the white logo frame, from which the home screen fades in.

*History:* the first build played the client HTML in a locked-down WebView; on a user's device it
showed only the Skip button, so it was replaced by the pre-rendered video.

To regenerate after any change to the client file:
`NODE_PATH=$(npm root -g) node tools/render_intro_video.js` (needs Playwright Chromium and ffmpeg).
