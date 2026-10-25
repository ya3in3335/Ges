"""
Builds android-app/app/src/main/assets/intro/intro.html from the client's animation
(branding/client-animation-src/oran-motion.html).

The client's code is copied verbatim from `const A = ...` up to the start of its editor
"player" section: embedded layers, timeline (TL), easing helpers and the deterministic
render(ctx, W, H, t). Only the editor UI (transport bar, format chips, layer list, web fonts)
is left out and replaced by a minimal full-screen player that plays once and then signals
completion by navigating to a sentinel URL that the Android WebView intercepts.

Usage: python3 tools/build_intro_html.py
"""
import pathlib, re, hashlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
src = (ROOT / "branding" / "client-animation-src" / "oran-motion.html").read_text(encoding="utf-8")

start = src.index("const A = ")
end = src.index("/* ---------- player ---------- */")
client_code = src[start:end]
assert "function render(ctx,W,H,t)" in client_code, "render() not found in client animation"
assert "fetch(" not in client_code and "XMLHttpRequest" not in client_code, "client code must be offline"

player = r"""
/* ---------- minimal Android player (not part of the client's animation) ---------- */
(function(){
  const cv=document.getElementById('cv'),ctx=cv.getContext('2d');
  const HOLD=0.45;                           // keep the final frame briefly before handing over
  let t0=null,done=false;
  function size(){
    const dpr=Math.min(window.devicePixelRatio||1,2);
    let w=Math.round(innerWidth*dpr),h=Math.round(innerHeight*dpr);
    const cap=1920/Math.max(w,h); if(cap<1){w=Math.round(w*cap);h=Math.round(h*cap)} // keeps low-end devices smooth
    cv.width=w;cv.height=h;
  }
  function finish(){ if(done)return; done=true; location.href='https://appassets.androidplatform.net/__intro_done'; }
  function frame(ts){
    if(t0===null)t0=ts;
    const t=Math.min((ts-t0)/1000,DUR);
    render(ctx,cv.width,cv.height,t);
    if((ts-t0)/1000>=DUR+HOLD){finish();return}
    requestAnimationFrame(frame);
  }
  addEventListener('resize',()=>{size()});
  ready.then(()=>{
    size();
    if(matchMedia('(prefers-reduced-motion: reduce)').matches){render(ctx,cv.width,cv.height,DUR);setTimeout(finish,600);return}
    requestAnimationFrame(frame);
  });
})();
"""

html = (
    '<!doctype html><html lang="ar"><head><meta charset="utf-8">'
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
    "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; script-src 'unsafe-inline'; style-src 'unsafe-inline'\">"
    "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}canvas{display:block;width:100vw;height:100vh}</style>"
    '</head><body><canvas id="cv"></canvas><script>\n'
    + client_code + player +
    "</script></body></html>"
)
out = ROOT / "android-app" / "app" / "src" / "main" / "assets" / "intro"
out.mkdir(parents=True, exist_ok=True)
(out / "intro.html").write_text(html, encoding="utf-8")
print("intro.html", len(html), "bytes; client code sha256", hashlib.sha256(client_code.encode()).hexdigest()[:16])
