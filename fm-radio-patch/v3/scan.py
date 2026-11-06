import sys,io,zipfile,colorsys,collections,struct
sys.path.insert(0,'.')
import arsc
from PIL import Image
z=zipfile.ZipFile(__import__("os").path.join(__import__("os").path.dirname(__file__), "..", "input", "FMRadio-orig.apk"))
b=bytearray(z.read("resources.arsc")); gs,res=arsc.entries(b)
names=collections.defaultdict(list)
for d in res:
    if d["vtype"]==3 and gs[d["vdata"]].startswith("res/"): names[gs[d["vdata"]]].append(d["type"]+"/"+d["key"])
def isred(r,g,bl):
    h,s,l=colorsys.rgb_to_hls(r/255,g/255,bl/255)
    return s>.35 and .08<l<.85 and (h<.1 or h>.94)   # red..orange (hue <36deg)
out=[]
for n in z.namelist():
    if n.endswith(".png"):
        im=Image.open(io.BytesIO(z.read(n))).convert("RGBA"); px=list(im.getdata())
        op=[p for p in px if p[3]>40]
        if not op: continue
        rd=[p for p in op if isred(*p[:3])]
        if len(rd)>=3:
            avg=tuple(sum(p[i] for p in rd)//len(rd) for i in range(3))
            out.append((len(rd)/len(op),len(rd),n,im.size,"#%02x%02x%02x"%avg,names.get(n)))
for o in sorted(out,reverse=True): print("%.2f %5d %s %s %s %s"%o)
