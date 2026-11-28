#!/usr/bin/env python3
"""Builds the MAXIDO store.   python3 build_catalog.py [store_folder] [base_url]      (default folder: ./store)

Reads   <store>/items/skins/<id>.json              a skin (theme): "name" + colours as "#RRGGBB"; "id" defaults to the file name
        <store>/items/packs/<id>/                  a sampler pack as a folder: audio files (+ optional pack.json)
        <store>/items/packs/<id>.zip               ...or as a zip of audio files (+ optional pack.json). Easiest to upload.
Writes  <store>/catalog.json and <store>/files/    what the apps download. With base_url (https://.../store) the catalog holds full
        addresses, which is what the app needs; without it they are relative (files/x.zip).

The ?v= part makes every changed file a new address, so a CDN can never serve an old copy next to a new catalog.
Bump "version" (in the skin file / pack.json) to make the apps show UPDATE. Output is byte-for-byte repeatable, so an unchanged
store produces no changes. Any mistake stops the build with a clear message (nothing half-published).
"""
import hashlib, json, os, re, shutil, sys, zipfile

LIMIT_SKIN, LIMIT_PACK, MAX_PADS = 256_000, 60_000_000, 16
ID_OK = re.compile(r"^[a-z0-9][a-z0-9_-]{0,39}$")
COLOR_OK = re.compile(r"^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
COLOR_KEYS = ["bg", "panel", "btnTop", "btnBottom", "line", "text", "card", "deckA", "deckADark", "deckALight", "deckB", "deckBDark", "deckBLight"]
AUDIO = {".mp3", ".ogg", ".wav", ".m4a", ".aac", ".flac"}
errors = []

def err(msg): errors.append(msg)
def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 16), b""):
            h.update(b)
    return h.hexdigest()
def pretty(stem): return re.sub(r"[_-]+", " ", stem).strip().title()

def build(store, base=""):
    src, out = os.path.join(store, "items"), store
    files = os.path.join(out, "files")
    if not os.path.isdir(src):
        sys.exit(f"{src} does not exist")
    shutil.rmtree(files, ignore_errors=True); os.makedirs(files)
    items, seen = [], set()

    def add(kind, meta, path):
        iid = meta["id"]
        if iid in seen: err(f"{kind} id '{iid}' is used twice"); return
        seen.add(iid)
        items.append({"id": iid, "type": kind, "name": meta.get("name", iid), "author": meta.get("author", ""), "desc": meta.get("desc", ""),
                      "version": int(meta.get("version", 1)), "url": (base.rstrip("/") + "/" if base else "") + "files/" + os.path.basename(path) + "?v=" + sha(path)[:12], "sha256": sha(path), "size": os.path.getsize(path)})

    # ---------------- skins
    sk = os.path.join(src, "skins")
    for f in sorted(os.listdir(sk)) if os.path.isdir(sk) else []:
        if not f.endswith(".json"): continue
        try: meta = json.load(open(os.path.join(sk, f), encoding="utf-8-sig"))
        except Exception as e: err(f"skin {f}: not valid JSON ({e})"); continue
        meta.setdefault("id", f[:-5])
        if not ID_OK.match(meta["id"]): err(f"skin {f}: bad id '{meta['id']}' (use a-z 0-9 _ - only, max 40)"); continue
        bad = [k for k in COLOR_KEYS if k in meta and not (isinstance(meta[k], str) and COLOR_OK.match(meta[k]))]
        if bad: err(f"skin {f}: colours must look like #RRGGBB: {', '.join(bad)}"); continue
        lay = meta.get("layout", {})
        if not isinstance(lay, dict) or not all(isinstance(v, (bool, int, float)) for v in lay.values()): err(f"skin {f}: 'layout' must be an object of true/false/number values"); continue
        if "shape" in meta and not isinstance(meta["shape"], (int, float)): err(f"skin {f}: 'shape' must be a number 0..2"); continue
        if not any(k in meta for k in COLOR_KEYS): err(f"skin {f}: has no colours"); continue
        dst = os.path.join(files, meta["id"] + ".json")
        with open(dst, "w", encoding="utf-8") as fh: json.dump(meta, fh, indent=1, sort_keys=True)
        if os.path.getsize(dst) > LIMIT_SKIN: err(f"skin {f}: bigger than {LIMIT_SKIN // 1000} KB"); continue
        add("skin", meta, dst)

    # ---------------- packs (folder or zip)
    pk = os.path.join(src, "packs")
    for d in sorted(os.listdir(pk)) if os.path.isdir(pk) else []:
        p = os.path.join(pk, d)
        stem = os.path.splitext(d)[0]
        data = {}                                   # file name -> bytes
        if os.path.isdir(p):
            for f in os.listdir(p):
                if os.path.isfile(os.path.join(p, f)): data[f] = open(os.path.join(p, f), "rb").read()
        elif d.lower().endswith(".zip"):
            try:
                with zipfile.ZipFile(p) as z:
                    for n in z.namelist():
                        base = n.rsplit("/", 1)[-1]
                        if base and not base.startswith(".") and not n.endswith("/") and "__MACOSX" not in n: data[base] = z.read(n)
            except Exception as e: err(f"pack {d}: not a valid zip ({e})"); continue
        else: continue
        audio = sorted(n for n in data if os.path.splitext(n)[1].lower() in AUDIO)
        if not (1 <= len(audio) <= MAX_PADS): err(f"pack {d}: needs 1 to {MAX_PADS} audio files ({', '.join(sorted(AUDIO))}), found {len(audio)}"); continue
        meta = {}
        if "pack.json" in data:
            try: meta = json.loads(data["pack.json"].decode("utf-8-sig"))
            except Exception as e: err(f"pack {d}: pack.json is not valid JSON ({e})"); continue
        meta.setdefault("id", stem)
        meta.setdefault("name", pretty(stem))
        if not ID_OK.match(meta["id"]): err(f"pack {d}: bad id '{meta['id']}' (use a-z 0-9 _ - only, max 40)"); continue
        pads = meta.get("pads")
        if not pads: pads = [{"name": pretty(os.path.splitext(a)[0]), "file": a} for a in audio]; meta["pads"] = pads
        missing = [x.get("file") for x in pads if x.get("file", "").rsplit("/", 1)[-1] not in data]
        if missing: err(f"pack {d}: pack.json lists files that are not there: {', '.join(map(str, missing))}"); continue
        dst = os.path.join(files, meta["id"] + ".zip")
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_STORED) as z:            # audio is already compressed; fixed date = repeatable bytes
            zi = zipfile.ZipInfo("pack.json", (1980, 1, 1, 0, 0, 0)); z.writestr(zi, json.dumps(meta, indent=1, sort_keys=True))
            for a in audio:
                zi = zipfile.ZipInfo(a, (1980, 1, 1, 0, 0, 0)); z.writestr(zi, data[a])
        if os.path.getsize(dst) > LIMIT_PACK: err(f"pack {d}: bigger than {LIMIT_PACK // 1_000_000} MB"); os.remove(dst); continue
        add("pack", meta, dst)

    if errors:
        print("STORE NOT BUILT. Fix these and upload again:")
        for e in errors: print("  -", e)
        sys.exit(1)
    with open(os.path.join(out, "catalog.json"), "w", encoding="utf-8") as fh:
        json.dump({"version": 1, "items": sorted(items, key=lambda i: (i["type"], i["name"].lower()))}, fh, indent=1)
    print(f"Store built: {sum(i['type'] == 'skin' for i in items)} skins, {sum(i['type'] == 'pack' for i in items)} sampler packs -> {out}/catalog.json")

if __name__ == "__main__":
    a = sys.argv[1:]
    if len(a) > 2 or (a and a[0].startswith("http")) or (len(a) == 2 and not a[1].startswith("https://")): sys.exit(__doc__)
    build(a[0] if a else "store", a[1] if len(a) == 2 else "")
