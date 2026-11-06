import sys,zipfile,struct,collections
sys.path.insert(0,'.')
import arsc
z=zipfile.ZipFile(__import__("os").path.join(__import__("os").path.dirname(__file__), "..", "input", "FMRadio-orig.apk"))
b=bytearray(z.read("resources.arsc")); gs,res=arsc.entries(b)
names=collections.defaultdict(list)
for d in res:
    if d["vtype"]==3 and gs[d["vdata"]].startswith("res/"): names[gs[d["vdata"]]].append(d["type"]+"/"+d["key"])
cname={d["id"]:d["key"] for d in res}
def scan(data):
    # binary xml: walk chunks; attributes in START_ELEMENT
    out=[]; pos=struct.unpack_from("<H",data,2)[0]
    sp=arsc._strpool(data,pos); pos+=struct.unpack_from("<I",data,pos+4)[0]
    while pos<len(data):
        t,hs,sz=struct.unpack_from("<HHI",data,pos)
        if t==0x102:
            ns,nm,astart,asz,ac=struct.unpack_from("<IIHHH",data,pos+16)[0:5]
            ao=pos+16+struct.unpack_from("<H",data,pos+24)[0]
            tag=sp[nm]
            for i in range(ac):
                a=ao+i*20; an=struct.unpack_from("<I",data,a+4)[0]
                vt=data[a+15]; vd=struct.unpack_from("<I",data,a+16)[0]
                if vt in(0x1c,0x1d): out.append((tag,sp[an],"#%08x"%vd))
        pos+=sz
    return out
for n in z.namelist():
    if n.endswith(".xml") and n.startswith("res/") :
        try: r=scan(z.read(n))
        except Exception as e: continue
        if r: print(n,names.get(n),r[:8])
