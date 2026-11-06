"""Minimal resources.arsc reader/patcher: lists entries, edits colour values in place."""
import struct, colorsys

def u16(b,o): return struct.unpack_from("<H",b,o)[0]
def u32(b,o): return struct.unpack_from("<I",b,o)[0]

def _strpool(b,o):
    # returns list of strings of the pool at offset o
    hs=u16(b,o+2); cnt=u32(b,o+8); flags=u32(b,o+16); sstart=u32(b,o+20)
    utf8=bool(flags&0x100); out=[]
    for i in range(cnt):
        so=o+sstart+u32(b,o+hs+4*i)
        if utf8:
            l=b[so]; so+=1 if not l&0x80 else 2
            if b[so-1 if False else so-1]&0x80 and False: pass
            # utf8: u8len (1-2), then u8 bytelen (1-2), then data
            p=o+sstart+u32(b,o+hs+4*i)
            c=b[p]; p+= 2 if c&0x80 else 1
            c=b[p]
            if c&0x80: bl=((c&0x7f)<<8)|b[p+1]; p+=2
            else: bl=c; p+=1
            out.append(b[p:p+bl].decode("utf-8","replace"))
        else:
            l=u16(b,so)
            if l&0x8000: l=((l&0x7fff)<<16)|u16(b,so+2); so+=2
            out.append(b[so+2:so+2+2*l].decode("utf-16le","replace"))
    return out

def entries(b):
    """yield dict(id,type,name,key,vtype,vdata,val_off,config,off) for every simple entry"""
    assert u16(b,0)==2
    o=u16(b,2); end=u32(b,4); gsp=None; res=[]
    pos=o
    gs=_strpool(b,pos); pos+=u32(b,pos+4)
    while pos<end:
        t=u16(b,pos); sz=u32(b,pos+4)
        if t==0x200:
            pid=u32(b,pos+8)&0xff
            hs=u16(b,pos+2)
            tsp=u32(b,pos+0x118-0x0)  # typeStrings offset (relative) at 0x108? fixed below
            ts_off=u32(b,pos+0x100+8) if False else u32(b,pos+8+4+256)
            ks_off=u32(b,pos+8+4+256+8)
            types=_strpool(b,pos+ts_off); keys=_strpool(b,pos+ks_off)
            p=pos+hs
            while p<pos+sz:
                tt=u16(b,p); tsz=u32(b,p+4)
                if tt==0x201:
                    thead=u16(b,p+2); tid=b[p+8]; cnt=u32(b,p+12); estart=u32(b,p+16)
                    cfg=b[p+20:p+20+u32(b,p+20)]
                    for i in range(cnt):
                        eo=u32(b,p+thead+4*i)
                        if eo==0xFFFFFFFF: continue
                        e=p+estart+eo
                        esz=u16(b,e); fl=u16(b,e+2); key=u32(b,e+4)
                        d=dict(id=(pid<<24)|(tid<<16)|i,type=types[tid-1],key=keys[key],cfg=cfg.hex(),off=e)
                        if fl&1:  # complex
                            d["complex"]=True; d["vtype"]=None; d["vdata"]=None
                        else:
                            vo=e+esz
                            d["vtype"]=b[vo+3]; d["vdata"]=u32(b,vo+4); d["val_off"]=vo+4; d["type_off"]=vo+3
                            if d["vtype"]==3: d["str"]=("g",d["vdata"])
                        res.append(d)
                p+=tsz
            pos+=sz
        else: pos+=sz
    return gs,res

def colors(b):
    gs,res=entries(b)
    for d in res:
        if d["vtype"] in (0x1c,0x1d,0x1e,0x1f): yield d
        
def argb(d):
    v=d["vdata"]; t=d["vtype"]
    if t==0x1c: return v
    if t==0x1d: return 0xFF000000|v
    return v
def set_color(b,d,new):
    struct.pack_into("<I",b,d["val_off"],new)
