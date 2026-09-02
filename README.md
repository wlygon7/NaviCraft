# NaviCraft

A Fabric mod that brings real GPS-style navigation to Minecraft — Forza Horizon-inspired route arrows, saved waypoints, and a HUD compass that actually tells you where you're going.

![Minecraft](https://img.shields.io/badge/Minecraft-26.2-brightgreen)
![Fabric](https://img.shields.io/badge/Fabric-Loader%200.19.5-blue)
![Fabric API](https://img.shields.io/badge/Fabric%20API-0.159.0%2B26.2-blue)
![Java](https://img.shields.io/badge/Java-25-orange)

## What it does

Instead of squinting at coordinates or fighting the vanilla compass, NaviCraft draws a flowing trail of translucent chevrons through the world leading straight to your destination, backed by a HUD bearing bar showing live distance and direction. Set a one-off destination or save permanent named waypoints — home, your base, a nether portal, wherever — and never get lost again.

## Features

- **`/gps navi <x> <y> <z>`** — Set an active navigation target (optional dimension override)
- **`/gps marker <x> <y> <z> <name> [--portal]`** — Save a named waypoint (supports `~ ~ ~` relative coordinates); `--portal` marks it as a dimension portal for cross-dimension routing
- **`/gps goto <name>`** — Navigate to a saved waypoint, with tab-completion
- **`/gps list`** — List all saved waypoints
- **`/gps remove <name>`** — Delete a waypoint
- **`/gps stop`** — Clear active navigation
- **Animated arrow trail** — Scrolling, fading directional chevrons rendered along your path, capped at 64 blocks for performance
- **HUD bearing bar** — Live distance, compass direction, and target name, with cross-dimension awareness
- **Portal-aware routing** — Navigating to a different dimension automatically routes you to a known portal marker first, then resumes to the real destination
- **Persistent waypoints** — Saved per-player, survive server restarts
- **Persistent active navigation** — Log out mid-trip, log back in, and NaviCraft picks up right where you left off
- **Configurable** — Arrow color, spacing, scroll speed, render distance, and HUD corner, via `config/navicraft.json`

## Requirements

| Component | Version |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.5+ |
| Fabric API | 0.159.0+26.2 |
| Java | 25 |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and `navicraft-<version>.jar` into your `mods/` folder
3. Launch the game

## Usage

```
# Save your base as a waypoint
/gps marker ~ ~ ~ home

# Mark your nether portal for smart cross-dimension routing
/gps marker <x> <y> <z> nether_portal --portal

# Head to a specific coordinate
/gps navi 12 87 233

# Head to a saved waypoint
/gps goto home

# See what you've saved
/gps list

# Stop navigating
/gps stop
```

Waypoints and active navigation are saved per-player and persist across sessions — log out mid-trip and pick right back up when you return.

## Architecture notes

- Path calculation sits behind a `PathProvider` interface. v1 ships a straight-line implementation with ground-snapping and height smoothing; road-following/A* pathfinding can be dropped in later without touching the renderer or commands.
- Rendering uses `LevelRenderEvents.COLLECT_SUBMITS` / `SubmitNodeCollector.submitCustomGeometry` (26.x's extract/submit renderer), throttled to recompute every 10–40 ticks and only after ~2.5 blocks of player movement — not every tick.
- HUD is built on `HudElementRegistry`.
- Waypoint and navigation state are stored as per-player JSON at `<world>/data/navicraft/waypoints/<uuid>.json`.

## Roadmap

- [ ] Road-following pathfinding (tagged path blocks or terrain A*) as a `PathProvider` swap-in
- [ ] Client-configurable arrow style/texture

## License

[MIT](LICENSE)

## Author

Built by [Wilson Ligon](https://lygonms.com).