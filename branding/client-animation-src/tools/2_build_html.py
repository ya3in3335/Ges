# Rebuilds ../oran-motion.html from template.html + layer PNGs (run after 1_segment_layers.py)
import json,base64,io,pathlib
from PIL import Image
D=pathlib.Path(__file__).resolve().parent
m=json.load(open(D/'meta.json'))
src=D.parent/'layers'
m['assets']={}
for nm in m['layers']:
    b=io.BytesIO(); Image.open(src/f'L_{nm}.png').save(b,'WEBP',quality=92,method=6)
    m['assets'][nm]='data:image/webp;base64,'+base64.b64encode(b.getvalue()).decode()
m['win']={'x':1025,'y':178,'w':68,'h':55}
s=(D/'template.html').read_text(encoding='utf-8').replace('__ASSETS__',json.dumps(m))
html='<!doctype html><html lang="ar"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0">'+s+'</body></html>'
(D.parent/'oran-motion.html').write_text(html,encoding='utf-8')
print('built oran-motion.html')
