![Nexora Banner](assets/header2.png)

# Nexora

**Build portals. Connect places. Travel instantly.**

A lightweight, standalone, server-side portal network plugin for **Minecraft Paper 26.3**.

**Version 1.2.0** · **Java 25** · **Maven**

</div>

> **Development release:** Nexora 1.2.0 is based on the tested 1.1.0 release; the new network commands need testing on a Paper 26.3 server, but edge cases and long-term reliability still need further validation. Back up your server before deploying.

## Overview

Nexora lets players construct water-filled teleportation gates in the world. Build a fully enclosed vertical frame with activation blocks, name a clock in an anvil, and use it to activate the frame. Gates with the same name form a network. Players can then walk into a gate to travel to another location, including another world if enabled.

No client-side mods, resource packs, or other server plugins are required.

Nexora is an **original implementation**. It does **not** use source code from CreativeGates or CreativeGates3.

## Features

- **In-world creation:** Build and activate portals without creation commands.
- **Configurable activation blocks:** Emerald blocks by default; change to another solid block in `config.yml`.
- **Flexible frames:** Fully enclosed vertical frames, including supported irregular outlines, up to a configurable interior-cell limit.
- **Named networks:** Gates sharing a name connect; names are normalized case-insensitively while preserving display capitalization.
- **Persistent gate UUIDs:** Every gate has its own identifier; gates and network settings are stored in SQLite.
- **Three routing modes:** `PAIR` (default), `ROUND_ROBIN`, and `RANDOM`.
- **Ownership checks:** By default, only the network owner or an administrator may add gates to an existing network.
- **Safe arrivals and cooldowns:** Destination-side exit checks and a configurable teleport cooldown.
- **Easy gate management:** Look at a gate and use `/nexora info` or `/nexora remove`; administrators can also use abbreviated IDs.
- **Visual and audio effects (new in 1.1.0):** Ambient particles, activation/deactivation effects, and teleport departure/arrival effects.
- **Reloadable settings:** `/nexora reload` refreshes both `config.yml` and `effects.yml`.

## Requirements

| Requirement | Version |
|---|---|
| Server | Paper 26.3 |
| Java runtime | Java 25 |
| Build tool (source only) | Maven 3.9+ |
| Client mods | None |
| Required server plugins | None |

## Installation

1. Build the source (see below) or obtain a Nexora 1.1.0 JAR.
2. Stop the Paper server.
3. Place the JAR in the server's `plugins/` directory.
4. Start the server to generate `plugins/Nexora/config.yml`, `plugins/Nexora/effects.yml`, and the SQLite database.
5. Customize settings and run `/nexora reload`, or restart the server.

**Upgrading:** Back up `plugins/Nexora/`, especially `gates.db`, before replacing the JAR. Version 1.2.0 preserves the existing SQLite schema; no migration is needed from 1.1.0.

## Creating and using gates

1. Construct a **fully enclosed vertical portal frame**. Include at least **two emerald blocks** in the frame boundary (or the configured activation material and count).
2. Rename a **clock** in an anvil, for example `Village`.
3. Hold the named clock and **right-click a frame block on its inward-facing side**, targeting an empty interior space.
4. The portal fills with water and is registered.
5. Construct a second portal and activate it with a clock bearing the **same name**.
6. Walk into either portal to teleport. Keep a **clear, dry, two-block-high standing area with a solid floor** beside the receiving gate.

The activation clock is not consumed. Portal activation currently requires a renamed clock and a frame whose interior is air or water.

### Naming and ownership

Network names are trimmed, Unicode NFKC-normalized, and lowercased with `Locale.ROOT` for matching. For example, `Village` and `VILLAGE` belong to the same network; the first network's display capitalization is retained.

The creator of the first gate owns the network. When `networks.require-authorization-to-join` is enabled, only that owner or a player with `nexora.admin` can add another gate to it. Each individual gate also records its creator's UUID.

### Routing modes

| Mode | Behavior |
|---|---|
| `PAIR` | **Default.** Maximum two gates per network. Attempting a third gate is rejected without altering the frame. |
| `ROUND_ROBIN` | Fixed directional cycle in stable UUID order; each source goes to the next eligible gate, wrapping at the end. The ordering survives restarts. |
| `RANDOM` | Chooses a random eligible destination other than the source gate. |

The global `networks.default-routing` setting applies to **new networks**. Owners can change their own networks with `/nexora network routing <mode>` while looking at a gate. Administrators can still use `/nexora routing <network> <mode>`. A network with more than two gates cannot be switched to `PAIR` until excess gates are removed.

An unpaired gate can exist but has no destination until another eligible gate joins its network.

## Commands

| Command | Description | Access |
|---|---|---|
| `/nexora help` | Show available commands | Everyone |
| `/nexora network info` | Inspect the targeted gate's network | Network info permission |
| `/nexora network info <name>` | Inspect a network by name (spaces supported) | Network info permission |
| `/nexora network list` | List your networks; admins see all | Network info permission |
| `/nexora network routing <pair\|round_robin\|random>` | Change routing of the targeted gate's network | Owner + manage permission / admin |
| `/nexora network routing <mode> <name>` | Change routing by network name, including from console | Owner + manage permission / admin |
| `/nexora info` | Inspect the gate you are looking at (within 8 blocks) | Player |
| `/nexora remove` | Remove the targeted gate, subject to ownership and permission checks | Owner / admin |
| `/nexora list` | List registered gates with short IDs | Admin |
| `/nexora remove <short-id>` | Remove a gate by a unique abbreviated UUID | Admin |
| `/nexora remove <uuid>` | Remove a gate by full UUID (also available from console) | Admin |
| `/nexora routing <network> <PAIR\|ROUND_ROBIN\|RANDOM>` | Change a network's routing mode | Admin |
| `/nexora reload` | Reload the main and effects configurations | Reload permission |

**Tip:** Look at the portal's water or adjacent frame before running `/nexora info` or `/nexora remove`. You do not need to type a UUID for normal removal.

## Permissions

| Permission | Description | Default |
|---|---|---|
| `nexora.use` | Enter gates and teleport | `true` |
| `nexora.create` | Activate new gates | `true` |
| `nexora.destroy.own` | Remove gates you created using `/nexora remove` | `true` |
| `nexora.admin` | Admin commands, removal by ID, and ownership override | `op` |
| `nexora.reload` | Reload `config.yml` and `effects.yml` | `op` |
| `nexora.network.info` | Inspect networks and list networks you own | `true` |
| `nexora.network.manage` | Change routing of networks you own | `true` |

The nodes work with LuckPerms or Paper's built-in permission system. The `nexora.destroy.own` check applies to command-based removal; frame-breaking protection separately checks ownership and requires explicit removal first.

Example LuckPerms grants:

```text
/lp group default permission set nexora.use true
/lp group default permission set nexora.create true
/lp group default permission set nexora.destroy.own true
/lp group admin permission set nexora.admin true
/lp group admin permission set nexora.reload true
```

The first three permissions already default to `true`, so explicit grants are optional.

## Configuration

`plugins/Nexora/config.yml`:

```yaml
activation:
  item: CLOCK
  activation-block: EMERALD_BLOCK
  minimum-activation-blocks: 2
  max-name-length: 32

portal:
  max-interior-blocks: 225
  allow-horizontal: false
  restore-interior-on-removal: true

networks:
  default-routing: PAIR
  require-authorization-to-join: true

teleportation:
  cooldown-seconds: 3
  allow-cross-world: true
```

| Setting | Description |
|---|---|
| `activation.item` | Declared activation item; the current implementation requires a named `CLOCK`. |
| `activation.activation-block` | Solid material required in the frame boundary, e.g. `GOLD_BLOCK`. |
| `activation.minimum-activation-blocks` | Minimum count of that material in the boundary. |
| `activation.max-name-length` | Maximum gate-name length. |
| `portal.max-interior-blocks` | Upper limit for portal interior detection. |
| `portal.allow-horizontal` | Reserved configuration; horizontal portals are **not yet implemented**. |
| `portal.restore-interior-on-removal` | Restore recorded interior blocks when a gate is removed, where safe. |
| `networks.default-routing` | Routing mode assigned when a network is first created. |
| `networks.require-authorization-to-join` | Restrict joining existing networks to the owner or admins. |
| `teleportation.cooldown-seconds` | Prevent immediate repeated teleportation. |
| `teleportation.allow-cross-world` | Permit destinations in other worlds. |

Changes to the activation material are applied to **newly created gates** after `/nexora reload`; existing gates do not need to be recreated.

## Effects (new in 1.1.0)

`plugins/Nexora/effects.yml`:

```yaml
ambient:
  enabled: true
  particle: ENCHANT
  interval-ticks: 20
  particles-per-cycle: 4
  player-range: 24

activation:
  particles:
    enabled: true
    particle: END_ROD
    count: 35
  sound:
    enabled: true
    type: BLOCK_BEACON_ACTIVATE
    volume: 0.7
    pitch: 1.2

deactivation:
  particles:
    enabled: true
    particle: SMOKE
    count: 20
  sound:
    enabled: true
    type: BLOCK_BEACON_DEACTIVATE
    volume: 0.6
    pitch: 1.0

teleport:
  departure:
    enabled: true
    particle: PORTAL
    count: 30
  arrival:
    enabled: true
    particle: END_ROD
    count: 20
  sound:
    enabled: true
    type: ENTITY_ENDERMAN_TELEPORT
    volume: 0.6
    pitch: 1.2
```

**Ambient effects** run periodically near active gates with nearby players. Particle type, count, interval, and detection range are configurable.

**Activation and deactivation effects** play when gates are created or removed.

**Teleport effects** create particle bursts at departure and arrival, accompanied by a configurable sound.

Effect settings can be changed and applied with `/nexora reload`. Invalid particle or sound identifiers may prevent the corresponding effect from appearing; use identifiers supported by your Paper version.

## Building from source

Install **JDK 25** and **Maven 3.9+**. From the project directory (containing `pom.xml`), run:

```bash
mvn clean package
```

The shaded plugin JAR will be generated at:

```text
target/nexora-1.2.0.jar
```

The build bundles SQLite JDBC. You do not need to install the JDBC library separately.

## Known limitations

- Only **vertical, water-filled** portals are implemented. Horizontal and alternative visual portal styles are not yet available.
- Portal safety checks require a valid landing space; not every enclosed or obstructed frame can serve as a destination.
- Network authorization currently supports the original owner and admins, not a player-managed membership list.
- The fixed round-robin order is based on gate UUIDs, not chronological creation order.
- Database access is synchronous in the current implementation.
- Database writes and world block changes are not atomic across unexpected crashes; recovery/reconciliation needs further work.
- Full coverage for explosions, pistons, bucket actions, and all possible world-edit interactions is not yet implemented.
- The project does not yet include a comprehensive automated test suite.

## Changelog

### 1.2.0 — Network Management

- Added `/nexora network info`, `/nexora network list`, and `/nexora network routing`.
- Network owners can change their own routing mode without administrator privileges.
- Added `nexora.network.info` and `nexora.network.manage` permissions.
- New network commands support names containing spaces and targeted-gate selection.
- Round-robin now uses a deterministic directional cycle, ordered by gate UUID.
- Existing administrator routing command retained for compatibility.
- Existing gate data and per-network routing modes preserved.


### 1.1.0 — Portal Effects Update

- Added ambient particles around active portals when players are nearby.
- Added configurable activation and deactivation particle/sound effects.
- Added teleport departure and arrival particle effects and teleport sounds.
- Introduced `effects.yml` with adjustable particle density, frequency, range, volume, and pitch.
- Extended `/nexora reload` to reload the effects configuration.
- No new permissions or database migration required.

### 1.0.3 — Targeting and Teleport Fixes

- Corrected destination portal exit orientation.
- Added targeted `/nexora remove` and improved `/nexora info`.
- Added admin removal using abbreviated UUIDs and tab completion.
- Enforced the `nexora.destroy.own` permission for owner-initiated command removal.

### 1.0.2 — Configuration and Arrival Fixes

- Fixed hardcoded emerald activation blocks so `activation.activation-block` is respected.
- Improved safe-arrival position searching.

## License

No CreativeGates or CreativeGates3 source code is included. A project license has not yet been selected; add a `LICENSE` file before public distribution.

### 1.2.2 — Help Menu & Startup Fix

- `/nexora help [1|2|3]` now displays three colour-coded pages of command descriptions, routing modes, examples, and permissions.
- Help visibility reflects permissions for management and administration commands.
- `/nexora` opens page 1; tab completion suggests help pages.
- Avoids repeated `effects.yml` save warnings at server startup.

### 1.2.2 — Clickable Help Navigation

- Added interactive Previous/Next arrows and direct page links to `/nexora help`.
- Hover tooltips explain the page destination; commands still work manually and from console.
