# Client intro animation — integration notes

**Supplied:** `oran-motion-src.zip` (kept unmodified in `branding/client-animation-src/`):
`oran-motion.html` (canvas + JavaScript player with embedded WebP layers), 12 PNG layers,
timeline template and the client's own export scripts.

**Finding:** the motion is procedural — a deterministic `render(ctx, W, H, t)` (9.0 s) that lays
out the logo for any width/height. A pre-rendered MP4 would have a fixed aspect ratio and would
be cropped or letterboxed on modern tall phones, changing the composition.

**Integration chosen:** a local WebView, justified because it is the only way to run the
client's code unchanged at the device's exact aspect ratio.
- `tools/build_intro_html.py` copies the client's code verbatim from `const A = …` to the start of
  its editor "player" section (layers, timeline `TL`, easing, `render`) and adds a ~25-line
  full-screen player: plays once, holds the last frame 0.45 s, then navigates to a sentinel URL.
  The editor UI and the Google-Fonts link (unused by `render`) are dropped.
- WebView hardening (`ui/intro/IntroScreen.kt`): `WebViewAssetLoader` (`https://appassets.androidplatform.net/assets/intro/…` only), every other request answered 403, `blockNetworkLoads`, no file/content access, no DOM storage, no `addJavascriptInterface`, CSP `default-src 'none'`, render-process crash → continue to app.
- Canvas resolution capped at 1920 px on the long side (devicePixelRatio ≤ 2) for low-end devices; `prefers-reduced-motion` shows the final frame.
- Skip button after 1.5 s, back press skips, 13 s safety timeout. Plays on cold start only; can be disabled in Settings. Not shown when the app is opened from a notification.
- The system starting window is plain black (no icon, Android 12+) so it blends into the animation's first frame; no branded splash was designed.
- The animation ends on a white frame with the logo; the home screen fades in from it.

**Optimisation:** none to the motion itself. Asset size: 258 KB HTML.
