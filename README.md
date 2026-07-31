# FancyMap

FancyMap is a Paper 1.21.11 plugin that opens a client-side world map with keyboard navigation, zoom, textures and interactive waypoints.

## Requirements

- Paper 1.21.11
- Java 21
- **[PacketEvents](https://modrinth.com/plugin/packetevents) 2.13.0+ (bắt buộc).** FancyMap không thể khởi động nếu PacketEvents chưa được cài đặt.
- PlaceholderAPI is optional. FancyMap placeholders work without it; install it to use placeholders from other plugins in FancyMap templates.

## Install

1. Download `FancyMap-<version>.jar` from [Releases](../../releases).
2. Put it and **PacketEvents** in the server `plugins` folder.
3. Restart the server.

The plugin creates `plugins/FancyMap/config.yml`, waypoint data and a `textures` folder. Add custom `PNG` textures there, then run `/fm reload`.

## Commands

| Command | Description |
| --- | --- |
| `/fm` | Open or close the map. |
| `/fm debug` | Toggle debug output. |
| `/fm reload` | Reload configuration and textures. |
| `/fm config <key> <value>` | Change a runtime map setting. |
| `/fm waypoint create <id> <name>` | Create a waypoint at your position. |
| `/fm waypoint remove <id>` | Remove a waypoint. |
| `/fm waypoint icon <id> <material\|texture>` | Set a Material or texture icon. |
| `/fm waypoint list` | Open the waypoint list. |
| `/fm waypoint seek <id>` | Open the map and center it on a waypoint. |
| `/fm waypoint tp <id>` | Teleport to a waypoint. |

Aliases: `/fancymap`, `/fm`, `/map`.

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `fancymap.use` | Everyone | Open and control the map. |
| `fancymap.debug` | OP | Debug output. |
| `fancymap.config` | OP | Change runtime config. |
| `fancymap.reload` | OP | Reload config and textures. |
| `fancymap.waypoint.list` | Everyone | Open the waypoint list. |
| `fancymap.waypoint.teleport` | OP | Teleport to a waypoint. |
| `fancymap.waypoint.manage` | OP | Create, remove and edit waypoints. |
| `fancymap.admin` | OP | All administrative permissions. |

## Controls and actions

Default controls are defined in `config.yml`:

- `W`, `A`, `S`, `D`: move the map cursor
- Mouse wheel: zoom
- `Shift`: close map
- `Shift` + movement: fast cursor movement
- `Space`: open waypoint list
- `F`: teleport to the hovered waypoint

The `actions` section can run commands for key presses or key chords. For example:

```yml
actions:
  shift: fancymap
  space+w: say moving north
```

Completed chords take precedence over shorter actions, so `space+w` does not also trigger `space`.

## Waypoint templates and placeholders

`waypoint-display` accepts MiniMessage and these FancyMap placeholders:

- `%fancymap_waypoint_id%`
- `%fancymap_waypoint_name%`
- `%fancymap_waypoint_world%`
- `%fancymap_waypoint_x%`, `%fancymap_waypoint_y%`, `%fancymap_waypoint_z%`
- `%fancymap_waypoint_icon%`

Player state placeholders:

- `%fancymap_open%`
- `%fancymap_hovering_waypoint%` / `%fancymap_hovering_waypoint_id%`
- `%fancymap_hovering_waypoint_name%`
- `%fancymap_hovering_waypoint_world%`
- `%fancymap_hovering_waypoint_x%`, `%fancymap_hovering_waypoint_y%`, `%fancymap_hovering_waypoint_z%`

## Build and release

```bash
./gradlew build
```

The GitHub **Release** workflow builds the JAR, creates tag `v<version>` from `gradle.properties`, and publishes a GitHub Release.
