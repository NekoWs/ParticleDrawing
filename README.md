# ParticleDrawing

[English](https://github.com/NekoWs/ParticleDrawing/blob/master/README.md) | [简体中文](https://github.com/NekoWs/ParticleDrawing/blob/master/README.zh-CN.md)

[![Build](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml/badge.svg)](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/work.nekow/particledrawing-neoforge-26.3?label=Maven%20Central)](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)
[![Modrinth](https://img.shields.io/modrinth/dt/particledrawing?label=Modrinth&logo=modrinth)](https://modrinth.com/mod/particledrawing)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://github.com/NekoWs/ParticleDrawing/blob/master/LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-26.2%20%2F%2026.3%20%2F%201.21.1-informational)
![NeoForge](https://img.shields.io/badge/NeoForge-26.2%20%2F%2026.3%20%2F%2021.1-informational)

ParticleDrawing is a server-authoritative particle effects library for NeoForge. Effects are built
primarily with the `/pdraw` command system or the Kotlin/Java API; it can also play `.pdrawc`
animations exported from the ParticleDrawing editor.

The server declares and owns effects, while clients evaluate and render them locally. Choreographed
groups, life curves and runtime emitters are sent once and evaluated on the client, so a running
effect costs little to no per-tick bandwidth.

## Highlights

- **Commands first.** Draw shapes, choreograph groups and declare emitters in one command. Works in
  chat, command blocks and data pack functions, with tab completion and in-game help.
- **API first.** Draw shapes and animate groups from Kotlin or Java with `Draw`, `ParticleGroup`,
  `ParticleBatch` and runtime emitters. No `.pdrawc` file or editor is required.
- **No resource pack for basic effects.** Built-in soft-dot and line shapes, vanilla particle
  textures, per-particle UVs, anisotropic sizes, additive blending and PNG registration are supported.
- **Declarative and cheap at runtime.** Sampling layouts, birth and motion formulas, life curves and
  program timelines are evaluated on the client. Bulk spawn, velocity, force and track operations are
  chunked automatically.
- **Multi-version.** One code base for Minecraft 26.2, 26.3 and 1.21.1 on NeoForge.
- **Optional editor playback.** `.pdrawc` animations with cameras, audio, function objects and
  runtime variables can be played from `<gameDir>/animations/`.

## Commands

`/pdraw` is available in chat, command blocks and `.mcfunction` files. In functions, omit the leading
`/`. Run `/pdraw help` for the topic index, or `/pdraw help cmd draw` / `/pdraw help cmd emitter` for
the full parameter list.

```mcfunction
# Draw a named group and animate it.
pdraw particle draw circle as sigil at ~ ~1 ~ radius 3 count 96 look color aqua size 0.15 glow
pdraw particle draw circle as sigil at ~ ~1 ~ radius 3 count 96 look color aqua also hexagram at ~ ~1 ~ radius 2.4 segments 24 look color gold then move 0 1 0 40 then spin 0 1 0 2 then delay 40 then fade_out 20

# Declare an emitter and start it in the same command.
pdraw particle emitter fire at ~ ~1 ~ sample count 60 spawn vec3(rand()-0.5,0,rand()-0.5) move vec3(0,0.06,0) look color orange size 0.1 glow life 30 rate 6 start

# Stop emitting, clear instances, or remove the definition.
pdraw particle stop fire
pdraw particle clear emitter fire
pdraw particle delete emitter fire
```

| Command | Description |
| --- | --- |
| `/pdraw particle draw …` | Draw one or more shapes, name the group, and append `then` actions. |
| `/pdraw particle emitter …` | Define an emitter from sampling, formulas, appearance and an emission window. |
| `/pdraw particle template` / `use …` | Save an emitter declaration and instantiate it with overrides. |
| `/pdraw particle start` / `burst` / `stop …` | Control continuous emission, one-shot bursts and running instances. |
| `/pdraw particle clear` / `delete …` | Clear live instances, or remove the named emitter, group or template. |
| `/pdraw particle advanced …` | Inspect recipes, edit live emitters, or append actions to an existing group. |
| `/pdraw animation …` | List, play, stop and debug `.pdrawc` animations; switch preview cameras. |
| `/pdraw camera …` | Drive a free camera pose for recordings and cinematics. |
| `/pdraw texture reload` | Hot-reload particle textures from disk. |
| `/pdraw particle field …` | Configure the programmatic force-field entrance. |

Useful pieces of the command grammar:

- `draw` accepts `dot`, `field`, `line`, `beam`, `helix`, `circle`, `disc`, `triangle`, `hexagram`,
  `rect`, `sphere`, `cuboid`, `text`, `sin` and `expr`.
- `stagger` and `enter fade|grow|fade_grow` animate each particle's entrance.
- `then move|spin|delay|recolor|fade_out|follow …` builds a group timeline.
- `look` controls color, size, texture, glow, blending and scene-aware effects.
- `emitter` accepts `sample`, `spawn`, `move`, `life`, `rate`, `window`, `collide`, `subemit`,
  `with environment`, `with probe` and `with on`.

More syntax and examples: [particle commands](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/particle-commands.md).

## API

Add the dependency for your game version:

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("work.nekow:particledrawing-neoforge-26.3:<version>")
    localRuntime("work.nekow:particledrawing-neoforge-26.3:<version>")
}
```

```toml
# META-INF/neoforge.mods.toml
[[dependencies.${mod_id}]]
modId = "particledrawing"
type = "required"
ordering = "AFTER"
```

For Minecraft 26.2, replace `neoforge-26.3` with `neoforge-26.2`. Published versions are available on
Maven Central: [26.2](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.2) ·
[26.3](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3).

Draw from the server main thread:

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

`Draw` returns a `ParticleGroup` for most shapes; the same API covers lines, curves, polylines,
polygons, spheres, cuboids and text points. Beyond one-shot groups:

- `ParticleBatch` manages particle streams whose members are added and removed over time.
- `ParticleManager.emitter(anchor)` declares runtime emitters with spacing or interval emission.
- `Effects` registers and plays named effects with movable anchors and playback clocks.
- `Animation.create { … }` builds `.pdrawc`-equivalent animations directly in code.
- Per-particle textures, UV frames, anisotropic sizes, fixed orientation, dynamic lighting and
  OGG/WAV audio are available from the same API.

Full API index: [api reference](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/api-reference.md).
More code examples: [guide](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/guide.md) and
[getting started](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/getting-started.md).

## Editor and `.pdrawc` animations

The ParticleDrawing editor saves projects as `.pdraw` and exports `.pdrawc` files for the runtime.
This workflow is optional; commands and the API cover effects that do not need an editor project.

1. Export an animation from the [editor](https://viewer.nekow.work/).
2. Put the file into `<gameDir>/animations/`.
3. Play it in game:

```mcfunction
pdraw animation play meteor
pdraw animation list
pdraw animation stop
```

Animations are loaded on demand, so the game does not need a restart after adding or replacing a
file. Playback also supports preview cameras, runtime function-object variables and timing diagnostics:

```mcfunction
pdraw animation camera <name>
pdraw animation var <name> <value>
pdraw animation debug
```

From code, use `ServerAnimationManager` to play a `ParticleAnimation` or a `.pdrawc` file, update
variables and stop playback. Cameras, audio assets, function objects and timeline tracks are part of
the animation format.

More: [animation playback](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/animation.md).

## Requirements

| Minecraft | Minimum NeoForge | Java |
| --- | --- | --- |
| 1.21.1 | 21.1.235 | 21 |
| 26.2 | 26.2.0.59 | 25 |
| 26.3 | 26.3.0.101 | 25 |

## Installation

### Players

1. Install NeoForge for Minecraft 26.2, 26.3 or 1.21.1.
2. Put the ParticleDrawing JAR for your game version into `mods/`.
3. Use `/pdraw` in game. For editor animations, also place `.pdrawc` files into
   `<gameDir>/animations/`.

Downloads: [Modrinth](https://modrinth.com/mod/particledrawing) ·
[GitHub Releases](https://github.com/NekoWs/ParticleDrawing/releases).

On a server, install the same ParticleDrawing version on the client and the server.

### Developers

The dependency coordinates are target-specific. To bundle ParticleDrawing in your own JAR:

```kotlin
jarJar(implementation("work.nekow:particledrawing-neoforge-26.3:<version>"))
```

Older releases keep the `work.nekow:particledrawing:<version>` coordinate. See the
[multi-version guide](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/multiversion.md) for
coordinates, run directories and migration notes.

## Documentation

- [Documentation index](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/README.md)
- [Getting started](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/getting-started.md)
- [Particle commands](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/particle-commands.md)
- [API reference](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/api-reference.md)
- [Animation playback](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/animation.md)
- [Multi-version layout](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/multiversion.md)
- [Known issues](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/known-issues.md)

## Building from source

```bash
git clone https://github.com/NekoWs/ParticleDrawing.git
cd ParticleDrawing
./gradlew build          # compile and run the test suite
./gradlew runClient      # start a development client
./gradlew runServer      # start a development server
```

The build requires JDK 25. The root build targets 26.3; the shared core targets Java 21. Build a
specific adapter with `./gradlew -p platforms/neoforge-26.2 build` or
`./gradlew -p platforms/neoforge-26.3 build`.

## Releasing

`mod_version` in `gradle.properties` is the single source of truth. Whenever it changes, the same
commit gets a matching `v<mod_version>` tag. Pushing the tag publishes to Maven Central through
`publish.yml`, publishes each target to Modrinth through `modrinth.yml`, and creates a GitHub Release
through `release.yml`. All workflows fail if the tag name and `mod_version` do not match.

To publish a GitHub Release for an existing tag, open **Actions → Publish GitHub Release → Run
workflow** and enter `v<version>`. It uses the built-in `GITHUB_TOKEN` with `contents: write`; no
extra secret is required. Rerunning replaces same-named attachments while preserving existing release
notes.

## License

Licensed under the [Apache License 2.0](https://github.com/NekoWs/ParticleDrawing/blob/master/LICENSE).
