# ParticleDrawing

[English](README.md) | [简体中文](README.zh-CN.md)

[![Build](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml/badge.svg)](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/work.nekow/particledrawing-neoforge-26.3?label=Maven%20Central)](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)
[![Modrinth](https://img.shields.io/modrinth/dt/particledrawing?label=Modrinth&logo=modrinth)](https://modrinth.com/mod/particledrawing)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-26.2%20%2F%2026.3-informational)
![NeoForge](https://img.shields.io/badge/NeoForge-26.2%20%2F%2026.3-informational)

A server-authoritative particle effects library for NeoForge. It plays the animations exported by the
[ParticleDrawing editor](https://viewer.nekow.work/) and exposes a Kotlin/Java API for drawing and
animating particles directly from code.

Particles are created and owned by the server, while clients evaluate and render them locally.
Declarative features such as choreographed groups, life curves and runtime emitters are sent once and
evaluated on the client, so a running effect costs little to no per-tick bandwidth.

## Features

- Play `.pdrawc` files from `<gameDir>/animations/` with a command, or from code through
  `ServerAnimationManager`, including runtime variable updates.
- Draw lines, circles, discs, curves, polylines, triangles, hexagrams, rectangles, spheres and cuboids
  from code with `Draw`, with gradient coloring and per-particle entrance delays.
- Record a timeline of moves, rotations, scaling, pulses, color fades and expression- or entity-driven
  motion on a `ParticleGroup`; the client plays it back at render-frame rate.
- Declare a trail or an aura once as a runtime emitter and let each client emit along the anchor, per
  distance travelled or per interval, with deterministic per-particle jitter.
- Spawn up to 256 particle specs per packet, with automatic chunking for the bulk position, velocity,
  force and track operations.
- Use per-particle textures, sub-rectangle UVs, anisotropic sizes, fixed orientation, additive
  blending, built-in soft-dot/line shapes and PNG registration that syncs itself to clients, without a
  resource pack.
- Register effects with movable anchors, control their playback clock, play OGG/WAV audio and light
  particles dynamically.

## Requirements

| Minecraft | Minimum NeoForge | Java |
| --- | --- | --- |
| 26.2 | 26.2.0.59 | 25 |
| 26.3 | 26.3.0.102 | 25 |

NeoForge 1.21.1 is the next planned target. The current downloads cover 26.2 and 26.3.

## Installation

### Players

1. Install NeoForge for Minecraft 26.2 or 26.3.
2. Install the JAR for your game version into `mods/`. [Modrinth](https://modrinth.com/mod/particledrawing) · [GitHub Releases](https://github.com/NekoWs/ParticleDrawing/releases)
3. Export an animation from the [editor](https://viewer.nekow.work/) and place the `.pdrawc` file into
   `<gameDir>/animations/`.
4. Use the `/pdraw` command in game. Animations are loaded on demand, so the game does not have to be
   restarted after adding or replacing one.

### Developers

The refactored releases use a separate Maven Central coordinate for each game version:

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("work.nekow:particledrawing-neoforge-26.3:<version>")
    localRuntime("work.nekow:particledrawing-neoforge-26.3:<version>")
}
```

The target-specific coordinates above apply to releases built from the multi-version layout.
Existing older releases retain `work.nekow:particledrawing:<version>`.
For Minecraft 26.2, replace `neoforge-26.3` with `neoforge-26.2`.

Choose a published `<version>` for your target on Maven Central. [26.2](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.2) · [26.3](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)

To bundle the library in your mod JAR, use
`jarJar(implementation("work.nekow:particledrawing-neoforge-26.3:<version>"))`.

Declare the dependency in `META-INF/neoforge.mods.toml` as well:

```toml
[[dependencies.${mod_id}]]
modId = "particledrawing"
type = "required"
ordering = "AFTER"
```

A minimal effect:

```kotlin
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.ParticleManager

val manager = ParticleManager.of(level)

Draw.circle(manager, Vec3(0.0, 70.0, 0.0), radius = 3.0, count = 80)
    .fadeIn(15)
    .spin(Vec3(0.0, 1.0, 0.0), Math.PI / 40)
    .delay(100)
    .stopContinuous()
    .fadeOut(20)
```

More in the [getting started guide](doc/getting-started.md).

## Commands

| Command                      | Description                                                     |
| ---------------------------- | --------------------------------------------------------------- |
| `/pdraw particle group ...`  | Create and manage named particle groups for data packs and command blocks. |
| `/pdraw particle add ...`    | Add dots, beams, helices, magic circles, spheres and other primitives. |
| `/pdraw particle emitter ...` | Configure sampling, images, formulas, anchors and particle lifetimes in steps. |
| `/pdraw particle program ...` | Choreograph anchors, entity following, movement, spin, pulses, variables and per-particle expressions. |
| `/pdraw animation list`      | List the animations available in `animations/`.                 |
| `/pdraw animation play <name> [pos]` | Play an animation at `pos`, or 3 blocks in front of the player. |
| `/pdraw animation stop`      | Stop every animation and effect playing in the current dimension. |
| `/pdraw animation camera <name\|stop>` | Preview through a camera of a playing animation.        |
| `/pdraw animation var <name> <value>` | Update a function object variable of the playing animations. |
| `/pdraw animation debug`     | Print evaluation time, particle count and timeline of playback. |
| `/pdraw texture reload`      | Reload particle textures from disk (client side only).          |

Use `group → add → program` for choreographed groups. For sampled curves, images and continuous emitters,
configure each step with `emitter`, then use `burst` or `start`. Both work in data pack functions and command blocks. See the [command guide](doc/guide.md#命令绘制与数据包) for the full
syntax and a ready-to-use magic circle example.
For a short command block example, see the [sin curve with the end rod texture](doc/guide.md#命令方块简单用法sin-曲线).
Use `texture=particle:minecraft:end_rod` to reuse a vanilla texture while retaining PD animation controls.
Frames follow particle age and lifetime. Use `field anchor` with `expression local` to share the group anchor,
and `lifetime=80` for a four-second lifetime. [Data pack setup and example functions](examples/commands/README.md)

## Documentation

| Document                                     | Contents                                                              |
| -------------------------------------------- | --------------------------------------------------------------------- |
| [getting-started.md](doc/getting-started.md) | Dependency setup, first effect, core concepts.                        |
| [guide.md](doc/guide.md)                     | Drawing, choreographed groups, handles, batches, emitters, animations. |
| [particle-commands.md](doc/particle-commands.md) | Step-by-step particle sources, formulas and image sampling. |
| [api-reference.md](doc/api-reference.md)     | Class index grouped by package.                                       |
| [known-issues.md](doc/known-issues.md)       | Known limitations and behavior differences against the editor.        |
| [multiversion.md](doc/multiversion.md)       | Core boundaries, platform builds, coordinates and migration.           |

## Building from source

```bash
git clone https://github.com/NekoWs/ParticleDrawing.git
cd ParticleDrawing
./gradlew build          # compile and run the test suite
./gradlew runClient      # start a development client
./gradlew runServer      # start a development server
```

The build requires JDK 25. The root build targets 26.3. Build either adapter with `./gradlew -p platforms/neoforge-26.2 build` or
`./gradlew -p platforms/neoforge-26.3 build`. The shared core targets Java 21. Architecture, artifact
coordinates, run directories, and migration notes are in [the multi-version guide](doc/multiversion.md).

## Releasing

`mod_version` in `gradle.properties` is the single source of truth. Whenever it changes, the same
commit gets a matching tag (`v<mod_version>`). Pushing such a tag publishes the artifact to Maven
Central through `publish.yml` using `publishAndReleaseToMavenCentral`. Central makes the artifacts
available after validation and synchronization. The tag also runs `modrinth.yml` to publish each target
and sync this README to the project page. [Modrinth](https://modrinth.com/mod/particledrawing)

`release.yml` builds both game targets and creates a
GitHub Release with their runtime JARs, sources JARs, SHA-256 checksums, and notes from commit titles since
the previous tag. ALPHA, BETA, and RC versions are marked as prereleases. All three workflows fail if
the tag name does not match `mod_version`; there is no release without a tag.

To publish a GitHub Release for an existing tag, open **Actions → Publish GitHub Release → Run workflow**
and enter `v<version>`. This checks out the tagged source and runs only the GitHub Release workflow.
It uses the built-in `GITHUB_TOKEN` with `contents: write`; no extra secret is required. Rerunning
replaces same-named attachments while preserving existing release notes.

## License

Licensed under the [Apache License 2.0](LICENSE).
