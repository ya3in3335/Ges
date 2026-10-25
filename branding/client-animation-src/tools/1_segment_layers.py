import numpy as np, cv2, json
S=2
src=cv2.imread('src.jpg')
im=cv2.resize(src,None,fx=S,fy=S,interpolation=cv2.INTER_CUBIC)
H,W=im.shape[:2]
f=im.astype(np.float32)
minc=f.min(axis=2)
alpha=np.clip((236-minc)/(236-175),0,1)
opaque=(minc<175).astype(np.uint8)
fill=cv2.inpaint(im,(1-opaque)*255,4,cv2.INPAINT_TELEA)
ink=(alpha>0.03).astype(np.uint8)
B,G,R=[fill[...,i].astype(int) for i in range(3)]
maroon=((B>=G-8)&(R<190)&(ink>0))
orange=(ink>0)&~maroon
# orange components
n,lab,st,cen=cv2.connectedComponentsWithStats(orange.astype(np.uint8),8)
layer=np.zeros((H,W),np.int16)-1  # ids: 0 tower,1 lionL,2 lionR,3..8 rays,9 text1,10 text2
# --- tower edges from rows 60..230 (1x) of main comp
main=lab[cen[:,1].argmin()] if False else None
big=np.argsort(-st[1:,4])[0]+1
comp=lab==big
ys=np.arange(70*S,225*S); L=[];Rr=[]
for y in ys:
    xs=np.where(comp[y])[0]; L.append(xs.min()); Rr.append(xs.max())
pl=np.polyfit(ys,L,1); pr=np.polyfit(ys,Rr,1)
yy,xx=np.mgrid[0:H,0:W]
inTower=(xx>=np.polyval(pl,yy)-2*S)&(xx<=np.polyval(pr,yy)+2*S)&(yy<400*S)
# --- rays: components entirely in ray zone
rays=[]
for i in range(1,n):
    x,y,w,h,a=st[i]
    if a<200*S*S: continue
    if w>3*h and (x+w<300*S or x>740*S) and y>400*S:
        rays.append(('comp',i))
# right top ray inside right lion comp: fit from pixels x>825
rl=[i for i in range(1,n) if st[i,0]>640*S and st[i,1]>290*S and st[i,1]<320*S and st[i,4]>3000*S*S][0]
pc=lab==rl
cols=range(830*S,int((st[rl,0]+st[rl,2]))-2)
tx=[];ty=[];by=[]
for x in cols:
    yv=np.where(pc[400*S:,x])[0]
    if len(yv): tx.append(x);ty.append(yv.min()+400*S);by.append(yv.max()+400*S)
pt=np.polyfit(tx,ty,1); pb=np.polyfit(tx,by,1)
band=pc&(yy>=np.polyval(pt,xx)-1.5*S)&(yy<=np.polyval(pb,xx)+1.5*S)&(xx>735*S)
rays.append(('mask',band))
# assign
tc=(np.polyval(pl,300*S)+np.polyval(pr,300*S))/2
for x in [0]:pass
raymasks=[]
for kind,v in rays:
    m=(lab==v) if kind=='comp' else v
    raymasks.append(m)
# sort rays: left top->bottom then right top->bottom
def key(m):
    ys_,xs_=np.where(m); return (xs_.mean()>tc, ys_.mean())
raymasks.sort(key=key)
anyray=np.zeros((H,W),bool)
for k,m in enumerate(raymasks):
    layer[m&orange]=3+k; anyray|=m
rest=orange&~anyray
tw=rest&inTower&comp
layer[tw]=0
rest2=rest&~tw
layer[rest2&(xx<tc)]=1
layer[rest2&(xx>=tc)]=2
# text lines by maroon comps
n2,lab2,st2,cen2=cv2.connectedComponentsWithStats(maroon.astype(np.uint8),8)
for i in range(1,n2):
    m=lab2==i
    layer[m]=9 if cen2[i,1]<531*S else 10

# ---- cleanup: text zone vs graphic zone
textzone=(yy>=415*S)&(xx>=145*S)&(xx<=875*S)&~((yy<470*S)&((xx<440*S)|(xx>600*S)))
txtzone2=(yy>530*S)|((yy>458*S)&(xx>300*S)&(xx<750*S))
bad=np.zeros((H,W),bool)
bad|=((layer==9)|(layer==10))&~textzone
bad|=((layer>=0)&(layer<=8))&txtzone2&(xx>145*S)&(xx<875*S)
good=(layer>=0)&~bad
# nearest label: for bad pixels choose label among graphic or text depending on zone
def nearest(valid_mask, targets):
    d,lbl=cv2.distanceTransformWithLabels((~valid_mask).astype(np.uint8),cv2.DIST_L2,5,labelType=cv2.DIST_LABEL_PIXEL)
    ys_,xs_=np.where(valid_mask)
    lut=np.zeros(lbl.max()+1,np.int16)-1
    # label ids correspond to zero pixels in scan order
    lut[lbl[ys_,xs_]]=layer[ys_,xs_]
    return lut[lbl[targets]]
gfx=good&(layer<=8); txt=good&(layer>=9)
b1=bad&~txtzone2
b2=bad&txtzone2
lay=layer.copy()
if b1.any(): lay[b1]=nearest(gfx,b1)
if b2.any(): lay[b2]=nearest(txt,b2)
layer=lay

bad3=((layer==1)&(xx<246*S))|((layer==2)&(xx>816*S))
rmask=(layer>=3)&(layer<=8)
if bad3.any(): layer[bad3]=nearest(rmask,bad3)

print('rays',len(raymasks),'tc',tc/S)
names=['tower','lionL','lionR']+[f'ray{k}' for k in range(len(raymasks))]+['text1','text2']
meta={'W':W,'H':H,'scale':S,'layers':{}}
rgba=np.dstack([fill,(alpha*255).astype(np.uint8)])
dbg=np.zeros((H,W,3),np.uint8)+255
pal=np.random.RandomState(3).randint(0,220,(20,3))
for idx,nm in enumerate(names):
    m=(layer==idx)
    # soften: include alpha neighbors -> dilate label to cover AA edges
    md=cv2.dilate(m.astype(np.uint8),np.ones((3,3),np.uint8))>0
    md&=(layer==idx)|(layer==-1)
    ys_,xs_=np.where(md)
    if len(ys_)==0: continue
    x0,x1,y0,y1=xs_.min(),xs_.max()+1,ys_.min(),ys_.max()+1
    out=rgba[y0:y1,x0:x1].copy(); out[...,3]=out[...,3]*md[y0:y1,x0:x1]
    cv2.imwrite(f'L_{nm}.png',out)
    meta['layers'][nm]={'x':int(x0),'y':int(y0),'w':int(x1-x0),'h':int(y1-y0)}
    dbg[m]=pal[idx]
cv2.imwrite('debug.png',cv2.resize(dbg,None,fx=0.5,fy=0.5))
# dome fit: tower bottom per column + ray inner tips
pts=[]
twm=layer==0
for x in range(int(np.polyval(pl,370*S))+6,int(np.polyval(pr,370*S))-6,4):
    yv=np.where(twm[:,x])[0]
    if len(yv): pts.append((x,yv.max()))
for m in raymasks:
    ys_,xs_=np.where(m)
    if xs_.mean()<tc: j=xs_.argmax()
    else: j=xs_.argmin()
    pts.append((xs_[j],ys_[j]))
P=np.array(pts,float)
A=np.c_[2*P[:,0],2*P[:,1],np.ones(len(P))]; b=(P**2).sum(1)
cx,cy,c=np.linalg.lstsq(A,b,rcond=None)[0]; r=np.sqrt(c+cx*cx+cy*cy)
print('dome',cx/S,cy/S,r/S)
meta['dome']={'cx':float(cx),'cy':float(cy),'r':float(r)}
# ray inner tip + angle
meta['rays']=[]
for k,m in enumerate(raymasks):
    ys_,xs_=np.where(m)
    left=xs_.mean()<tc
    j=xs_.argmax() if left else xs_.argmin()
    o=xs_.argmin() if left else xs_.argmax()
    meta['rays'].append({'name':f'ray{k}','ix':int(xs_[j]),'iy':int(ys_[j]),'ox':int(xs_[o]),'oy':int(ys_[o]),'left':bool(left)})
json.dump(meta,open('meta.json','w'),indent=1)
print(json.dumps(meta['layers']))
