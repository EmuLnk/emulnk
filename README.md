<p align="center">
  <img src=".github/assets/logo.svg" alt="EmuLnk" width="128"/>
</p>

<p align="center">
  <strong>Emulator companion app that turns your second screen into a themed dashboard.</strong><br/>
  Built for dual-display handhelds.
</p>

<p align="center">
  <a href="https://github.com/EmuLnk/emulnk/releases"><img src="https://img.shields.io/github/v/release/EmuLnk/emulnk?include_prereleases&style=for-the-badge&color=B47CFF&label=Release&logoColor=white&logo=github" alt="Release"/></a>
  &nbsp;
  <img src="https://img.shields.io/badge/Android-8.0%2B-00E5FF?style=for-the-badge&logo=android&logoColor=white" alt="Android 8.0+"/>
  &nbsp;
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin"/>
  &nbsp;
  <a href="https://github.com/EmuLnk/emulnk/releases"><img src="https://img.shields.io/github/downloads/EmuLnk/emulnk/total?style=for-the-badge&color=00E5FF&label=Downloads&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIGhlaWdodD0iMjQiIHZpZXdCb3g9IjAgLTk2MCA5NjAgOTYwIiB3aWR0aD0iMjQiIGZpbGw9IndoaXRlIj48cGF0aCBkPSJNNDgwLTMyMCAyODAtNTIwbDU2LTU4IDEwNCAxMDR2LTMyNmg4MHYzMjZsMTA0LTEwNCA1NiA1OC0yMDAgMjAwWk0yNDAtMTYwcS0zMyAwLTU2LjUtMjMuNVQxNjAtMjQwdi0xMjBoODB2MTIwaDQ4MHYtMTIwaDgwdjEyMHEwIDMzLTIzLjUgNTYuNVQ3MjAtMTYwSDI0MFoiLz48L3N2Zz4=" alt="Downloads"/></a>
</p>

<p align="center">
  <a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/EmuLnk/emulnk"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="55" alt="Get it on Obtainium"/></a>
</p>

---

## What It Does

EmuLnk connects to emulators over UDP, reads game memory in real time, and renders themed overlays — health bars, inventories, maps, enemy stats — on your second screen. Themes are HTML/CSS/JS WebViews driven by live data defined in JSON profiles.

<p align="center">
  <em>Screenshots coming soon</em>
</p>

## Supported Emulators

| Emulator | Systems | Fork |
|----------|---------|------|
| **RetroArch** | SNES, Genesis, NES, GB, GBC, GBA, PS1, N64 | [`retroarch-lnk`](https://github.com/EmuLnk/retroarch-lnk) |
| **Dolphin** | GameCube, Wii | [`dolphin-lnk`](https://github.com/EmuLnk/dolphin-lnk) |
| **PPSSPP** | PSP | [`ppsspp-lnk`](https://github.com/EmuLnk/ppsspp-lnk) |

> [!NOTE]
> Each emulator fork adds the EmuLnk binary UDP protocol. Install the fork alongside EmuLnk to use it.

## How It Works

```mermaid
flowchart TB
    subgraph S1["Screen 1"]
        EMU["Emulator"]
    end

    subgraph BG[" "]
        APP["EmuLnk App"]
    end

    subgraph S2["Screen 2"]
        THEME["Theme WebView"]
    end

    EMU -- "game serial · UDP" --> APP
    APP -- "memory read · UDP" --> EMU
    APP -- "live data · JS Bridge" --> THEME

    style S1 fill:#1a1730,stroke:#B47CFF,color:#B47CFF
    style S2 fill:#1a1730,stroke:#00E5FF,color:#00E5FF
    style BG fill:none,stroke:none
    style EMU fill:#252142,stroke:#B47CFF,color:#e0e0e0
    style APP fill:#252142,stroke:#B47CFF,color:#e0e0e0
    style THEME fill:#252142,stroke:#00E5FF,color:#e0e0e0
```

1. **Detect** — Sends a UDP request, emulator responds with a platform-prefixed serial (e.g. `SNES:SUPER METROID`)
2. **Match** — Serial maps to a profile defining memory addresses and data types
3. **Poll** — Data points are read from emulator memory each frame
4. **Render** — Live data is pushed to the theme WebView via JavaScript bridge

## Installation

Download the latest APK from [Releases](https://github.com/EmuLnk/emulnk/releases) or import the full EmuLnk suite into [Obtainium](https://github.com/ImranR98/Obtainium):

> [!TIP]
> **[`obtainium.json`](obtainium.json)** — Includes EmuLnk app + all emulator forks + repo tracking, grouped under one category.

## Building from Source

```bash
git clone https://github.com/EmuLnk/emulnk.git
cd emulnk
./gradlew assembleDebug
```

Requires Android SDK 34+ and JDK 17.

## Creating Themes

Themes are self-contained folders with HTML, CSS, JS, and a `theme.json` manifest. See the full documentation:

| Resource | Description |
|----------|-------------|
| [Getting Started](https://github.com/EmuLnk/emulnk-repo/wiki/Getting-Started) | First theme walkthrough |
| [Theme Format](https://github.com/EmuLnk/emulnk-repo/wiki/Theme-Format) | Manifest and file structure |
| [Theme API](https://github.com/EmuLnk/emulnk-repo/wiki/Theme-API) | JavaScript bridge methods |
| [Profile Format](https://github.com/EmuLnk/emulnk-repo/wiki/Profile-Format) | Data point definitions |
| [Platform Quirks](https://github.com/EmuLnk/emulnk-repo/wiki/Platform-Quirks) | System-specific memory notes |

Browse community themes and profiles in [`emulnk-repo`](https://github.com/EmuLnk/emulnk-repo).

## Project Repos

| Repo | Description |
|------|-------------|
| **`emulnk`** | **Android companion app** |
| [`emulnk-repo`](https://github.com/EmuLnk/emulnk-repo) | Themes, profiles, and system configs |
| [`retroarch-lnk`](https://github.com/EmuLnk/retroarch-lnk) | RetroArch fork with UDP protocol |
| [`dolphin-lnk`](https://github.com/EmuLnk/dolphin-lnk) | Dolphin fork with UDP protocol |
| [`ppsspp-lnk`](https://github.com/EmuLnk/ppsspp-lnk) | PPSSPP fork with UDP protocol |

## License

[GPLv3](LICENSE)
