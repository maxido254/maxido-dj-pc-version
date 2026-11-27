# MAXIDO store (no website needed)

The store lives in **your own GitHub repo**, the same one that builds the APK. The repo must be **public** (it already has to be for the download link).

## How it works (already set up)
Everything is in this repo's `store/` folder. `store/catalog.json` is the list the app reads. The Build APK workflow puts its address into the app, so the
APK you build already knows where the store is. The repo must stay **public**. To check in a browser open
`https://raw.githubusercontent.com/maxido254/maxido-dj-pc-version/main/store/catalog.json`.
(Rebuild the APK once after this was added; after that you never rebuild it for store changes.)

## Upload a new theme or sampler (every time, 2 minutes, from any browser)
Repo > `store/items/skins/` (themes) or `store/items/packs/` (sampler zips) > **Add file > Upload files** > Commit. About a minute later **Actions > Publish store** turns green and the phones see it
after pressing REFRESH. No new APK. A red X means a mistake; click it, the message says exactly what to fix.

| What | Where | Format |
|---|---|---|
| Theme (skin) | `store/items/skins/<id>.json` | `{ "name": "Neon Red", "author": "you", "desc": "...", "bg": "#0A0506", "panel": "#140B0D", "deckA": "#FF3B4E", ... }` colours as `#RRGGBB`. Keys you leave out keep the default. Copy `neon-red.json` and change the colours. |
| Sampler pack | `store/items/packs/<id>.zip` | A zip of 1 to 16 audio files (mp3 ogg wav m4a aac flac). Pad names come from the file names. Optional `pack.json` inside: `{ "name": "Afro Hits", "author": "you", "desc": "...", "pads": [ { "name": "Horn", "file": "horn.wav" } ] }` |
| Update | same file again | Raise `"version"` (skin file or `pack.json`) so the app shows UPDATE. |
| Remove | delete the file | |

`<id>` = lowercase letters, digits, `_` `-` (max 40). Limits: skin 256 KB, pack 60 MB. Only upload audio you own or are licensed to share.
The app checks every download against a SHA-256 in the catalog and installs nothing that does not match.

## Costs
Free. GitHub gives plenty of space and bandwidth for this size of store; keep packs small (a few MB each is plenty for pads).
