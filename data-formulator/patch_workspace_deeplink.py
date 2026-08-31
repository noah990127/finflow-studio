from pathlib import Path
import sys


bundle = Path(sys.argv[1])
source = bundle.read_text(encoding="utf-8")
needle = "},[u]),ct=reactExports.useCallback"
legacy_replacement = (
    "},[u]),reactExports.useEffect(()=>{const vt=new URLSearchParams(window.location.search).get(\"workspace\"),"
    "Yt=vt&&ce.find(Rt=>Rt.id===vt);Yt&&p?.id!==vt&&Je(vt,Yt.display_name)},[ce,p?.id,Je]),"
    "ct=reactExports.useCallback"
)
replacement = (
    "},[u]),FinFlowDeepLinkEffect=reactExports.useEffect(()=>{const vt=new URLSearchParams(window.location.search).get(\"workspace\"),"
    "Yt=vt&&ce.find(Rt=>Rt.id===vt);Yt&&p?.id!==vt&&Je(vt,Yt.display_name)},[ce,p?.id,Je]),"
    "ct=reactExports.useCallback"
)

if replacement in source:
    print("Data Formulator workspace deep-link patch is already installed")
elif legacy_replacement in source:
    bundle.write_text(source.replace(legacy_replacement, replacement), encoding="utf-8")
    print("Updated Data Formulator workspace deep-link patch")
elif source.count(needle) != 1:
    raise SystemExit(f"Expected one Data Formulator patch point, found {source.count(needle)}")
else:
    bundle.write_text(source.replace(needle, replacement), encoding="utf-8")
    print("Installed Data Formulator workspace deep-link patch")
