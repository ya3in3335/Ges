# python3 3_render_video.py 1920 1080 out.mp4   (needs: pip install playwright && playwright install chromium, and ffmpeg)
import asyncio,base64,subprocess,sys,pathlib
PAGE=(pathlib.Path(__file__).resolve().parent.parent/'oran-motion.html').as_uri()
from playwright.async_api import async_playwright
W,H,name=int(sys.argv[1]),int(sys.argv[2]),sys.argv[3]
FPS=60;DUR=9.5
async def main():
    ff=subprocess.Popen(['ffmpeg','-y','-loglevel','error','-f','image2pipe','-framerate',str(FPS),'-i','-','-c:v','libx264','-preset','veryfast','-crf','16','-pix_fmt','yuv420p','-movflags','+faststart',name],stdin=subprocess.PIPE)
    async with async_playwright() as p:
        b=await p.chromium.launch(args=['--use-gl=swiftshader']); pg=await b.new_page(viewport={'width':800,'height':600})
        await pg.goto(PAGE); await pg.evaluate('window.__ready')
        n=int(DUR*FPS)
        for i in range(n):
            t=min(i/FPS,9.0)
            d=await pg.evaluate(f'window.__render({W},{H},{t}).toDataURL("image/jpeg",0.95)')
            ff.stdin.write(base64.b64decode(d.split(',')[1]))
        await b.close()
    ff.stdin.close();ff.wait()
asyncio.run(main())
