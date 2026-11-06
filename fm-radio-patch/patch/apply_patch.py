import sys,re
root=sys.argv[1]
S=root+"/smali/com/android/fmradio/"
# --- FmService
p=S+"FmService.smali"
t=open(p,encoding="utf-8",newline="").read()
nl="\r\n" if "\r\n" in t else "\n"
t=t.replace("\r\n","\n")
call="    invoke-direct {p0}, Lcom/android/fmradio/FmService;->xyBroadcastRds()V\n\n"
# setPs / setLRText: insert before ':cond_0' of the change branch
for key,name in (("key_ps_info","setPs"),("key_rt_info","setLRText")):
    m=re.search(r"\.method private "+name+r"\(Ljava/lang/String;\)V.*?\.end method",t,re.S)
    body=m.group(0)
    assert body.count("    :cond_0\n    return-void")==1
    nb=body.replace("    :cond_0\n    return-void",call+"    :cond_0\n    return-void")
    t=t.replace(body,nb)
helper='''
.method private xyBroadcastRds()V
    .locals 3

    :try_start_0
    new-instance v0, Landroid/content/Intent;

    const-string v1, "xy.radio.rds"

    invoke-direct {v0, v1}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V

    const-string v1, "freq"

    iget v2, p0, Lcom/android/fmradio/FmService;->mCurrentStation:I

    invoke-virtual {v0, v1, v2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;I)Landroid/content/Intent;

    const-string v1, "ps"

    iget-object v2, p0, Lcom/android/fmradio/FmService;->mPsString:Ljava/lang/String;

    invoke-virtual {v0, v1, v2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    const-string v1, "rt"

    iget-object v2, p0, Lcom/android/fmradio/FmService;->mRtTextString:Ljava/lang/String;

    invoke-virtual {v0, v1, v2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    const-string v1, "pi"

    invoke-static {}, Lcom/android/fmradio/XyMediaSession;->publishedPi()I

    move-result v2

    invoke-virtual {v0, v1, v2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;I)Landroid/content/Intent;

    const-string v1, "af"

    invoke-static {}, Lcom/android/fmradio/XyMediaSession;->publishedAf()[I

    move-result-object v2

    invoke-virtual {v0, v1, v2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;[I)Landroid/content/Intent;

    const-string v1, "afKnown"

    invoke-static {}, Lcom/android/fmradio/XyMediaSession;->publishedAfKnown()[I

    move-result-object v2

    invoke-virtual {v0, v1, v2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;[I)Landroid/content/Intent;

    invoke-virtual {p0, v0}, Lcom/android/fmradio/FmService;->sendBroadcast(Landroid/content/Intent;)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    return-void
.end method
'''
t=t.rstrip("\n")+"\n"+helper
open(p,"w",encoding="utf-8",newline="").write(t.replace("\n",nl))
# --- FmMainActivity.sendCurFreqToOther
p=S+"FmMainActivity.smali"
t=open(p,encoding="utf-8",newline="").read()
nl="\r\n" if "\r\n" in t else "\n"
t=t.replace("\r\n","\n")
m=re.search(r"\.method private sendCurFreqToOther\(I\)V.*?\.end method",t,re.S)
body=m.group(0)
anchor="    :goto_0\n    iget-object p1, p0, Lcom/android/fmradio/FmMainActivity;->mContext:Landroid/content/Context;\n"
assert body.count(anchor)==1
ins='''    :goto_0
    iget-object v0, p0, Lcom/android/fmradio/FmMainActivity;->mService:Lcom/android/fmradio/FmService;

    if-eqz v0, :xy_skip

    invoke-virtual {v0}, Lcom/android/fmradio/FmService;->getPs()Ljava/lang/String;

    move-result-object v2

    invoke-static {v2}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z

    move-result v3

    if-nez v3, :xy_nops

    const-string v3, "ps"

    invoke-virtual {v1, v3, v2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    :xy_nops
    invoke-virtual {v0}, Lcom/android/fmradio/FmService;->getRtText()Ljava/lang/String;

    move-result-object v2

    invoke-static {v2}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z

    move-result v3

    if-nez v3, :xy_skip

    const-string v3, "rt"

    invoke-virtual {v1, v3, v2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;

    :xy_skip
    iget-object p1, p0, Lcom/android/fmradio/FmMainActivity;->mContext:Landroid/content/Context;
'''
t=t.replace(body,body.replace(anchor,ins))
open(p,"w",encoding="utf-8",newline="").write(t.replace("\n",nl))

# --- session hooks in FmService (v2): start / refresh / stop of XyMediaSession
p=S+"FmService.smali"
t=open(p,encoding="utf-8",newline="").read()
nl="\r\n" if "\r\n" in t else "\n"
t=t.replace("\r\n","\n")
SES="Lcom/android/fmradio/XyMediaSession;"
def hook(method, anchor_re, ins, where):
    global t
    m=re.search(r"\.method "+method+r".*?\.end method",t,re.S)
    body=m.group(0)
    if where=="end":   # before the last return-void
        i=body.rindex("    return-void")
        nb=body[:i]+ins+body[i:]
    else:
        mm=re.search(anchor_re,body,re.S); assert mm
        nb=body[:mm.end()]+ins+body[mm.end():]
    t=t.replace(body,nb)
hook(r"public onCreate\(\)V",None,
     "    invoke-static {p0}, "+SES+"->start(Lcom/android/fmradio/FmService;)V\n\n","end")
hook(r"public onDestroy\(\)V",r"\.locals 3\n\n",
     "    invoke-static {}, "+SES+"->stop()V\n\n","start")
hook(r"private notifyActivityStateChanged\(Landroid/os/Bundle;\)V",r"\.end annotation\n\n",
     "    invoke-static {p0}, "+SES+"->refresh(Lcom/android/fmradio/FmService;)V\n\n","start")
open(p,"w",encoding="utf-8",newline="").write(t.replace("\n",nl))

# --- v8.3: null guards in FmMainActivity$4.handleMessage (stock NPE when the station is not in the DB / the bundle has no RT)
p=S+"FmMainActivity$4.smali"
t=open(p,encoding="utf-8",newline="").read()
nl="\r\n" if "\r\n" in t else "\n"
t=t.replace("\r\n","\n")
# LISTEN_PS_CHANGED: FmStation.getStationName returns null for a frequency without a DB row; equals("") then skips the name UI
a="    invoke-static {p1, v0}, Lcom/android/fmradio/FmStation;->getStationName(Landroid/content/Context;I)Ljava/lang/String;\n\n    move-result-object p1\n"
assert t.count(a)==1
t=t.replace(a,a+"\n    if-nez p1, :xy_nn_name\n\n    const-string p1, \"\"\n\n    :xy_nn_name\n")
# LISTEN_RT_CHANGED: bundle string may be missing
a="Landroid/os/Bundle;->getString(Ljava/lang/String;)Ljava/lang/String;\n\n    move-result-object p1\n"
assert t.count(a)==1
t=t.replace(a,a+"\n    if-nez p1, :xy_nn_rt\n\n    const-string p1, \"\"\n\n    :xy_nn_rt\n")
open(p,"w",encoding="utf-8",newline="").write(t.replace("\n",nl))
