# ParticleDrawing

[English](https://github.com/NekoWs/ParticleDrawing/blob/master/README.md) | [简体中文](https://github.com/NekoWs/ParticleDrawing/blob/master/README.zh-CN.md)

[![Build](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml/badge.svg)](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/work.nekow/particledrawing-neoforge-26.3?label=Maven%20Central)](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)
[![Modrinth](https://img.shields.io/modrinth/dt/particledrawing?label=Modrinth&logo=modrinth)](https://modrinth.com/mod/particledrawing)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://github.com/NekoWs/ParticleDrawing/blob/master/LICENSE)

ParticleDrawing is a particle effects library for Minecraft on NeoForge. Use `/pdraw` in chat, command
blocks, or data pack functions to draw shapes, animate particle groups, and run emitters. Mod
developers can build effects with the Kotlin and Java API. The mod also plays `.pdrawc` animations
exported from the ParticleDrawing editor.

The server sends group animations, lifetime curves, and client-side emitter configurations once.
Clients evaluate and render those effects locally. Bulk spawn, velocity, force, and
tracking updates are split into packets automatically.

Built-in soft dots and lines work without a custom resource pack, as do vanilla particle textures.
ParticleDrawing also supports custom PNG textures, per-particle UVs, independent width and height, fixed
orientation, additive blending, and dynamic lighting.

## Installation

| Minecraft | Minimum NeoForge | Java |
| --- | --- | --- |
| 1.21.1 | 21.1.235 | 21 |
| 26.2 | 26.2.0.59 | 25 |
| 26.3 | 26.3.0.101 | 25 |

1. Install NeoForge for your Minecraft version.
2. Place the matching ParticleDrawing JAR in `mods/`. In multiplayer, install the same ParticleDrawing
   version on the server and each client.
3. In game, run `/pdraw help` with an account that has command permissions.

Download from [Modrinth](https://modrinth.com/mod/particledrawing) or [GitHub Releases](https://github.com/NekoWs/ParticleDrawing/releases).

## Commands

Start with a ring. The second example replaces it with a ring and hexagram that rise, spin, and fade.
The examples use `.mcfunction` syntax. In chat, add a leading `/`.

```mcfunction
# Draw a glowing ring named sigil.
pdraw particle draw circle as sigil at ~ ~1 ~ radius 3 count 96 look color aqua size 0.15 glow

# Replace sigil with two shapes and animate them as a group.
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
| `/pdraw particle emitter …` | Set sampling, spawn and motion formulas, appearance, and emission timing. |
| `/pdraw particle template` / `use …` | Save an emitter declaration and instantiate it with overrides. |
| `/pdraw particle start` / `burst` / `stop …` | Start continuous emission, emit a single burst, or stop spawning. |
| `/pdraw particle clear` / `delete …` | Clear live instances, or remove the named emitter, group or template. |
| `/pdraw particle advanced …` | Inspect recipes, edit live emitters, or append actions to an existing group. |
| `/pdraw animation …` | List, play, stop, and inspect `.pdrawc` animations. |
| `/pdraw camera …` | Control a free camera for recordings and cinematics. |
| `/pdraw texture reload` | Hot-reload particle textures from disk. |
| `/pdraw particle field …` | Configure particle force fields. |

Combine these options to shape and animate an effect.

- `draw` accepts `dot`, `field`, `line`, `beam`, `helix`, `circle`, `disc`, `triangle`, `hexagram`,
  `rect`, `sphere`, `cuboid`, `text`, `sin` and `expr`.
- `stagger` and `enter fade|grow|fade_grow` animate each particle's entrance.
- `then move|spin|delay|recolor|fade_out|follow …` builds a group timeline.
- `look` controls color, size, texture, glow, blending and scene-aware effects.
- `emitter` accepts `sample`, `spawn`, `move`, `life`, `rate`, `window`, `collide`, `subemit`,
  `with environment`, `with probe` and `with on`.

Press Tab for available arguments, or run `/pdraw help cmd draw` or `/pdraw help cmd emitter`
for command-specific help. [Particle command syntax and examples](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/particle-commands.md)

## API

Add Maven Central to your Gradle repositories and use the artifact for your Minecraft version.
The Kotlin DSL example targets Minecraft 26.3. Replace `<version>` with a published ParticleDrawing version.

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

[Minecraft 1.21.1](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-1.21.1) ·
[Minecraft 26.2](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.2) ·
[Minecraft 26.3](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)

Call the drawing API on the server thread with a `ServerLevel`. This example draws a ring that
fades in as it spins, then stops spinning and fades out.

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

- `Draw` creates a `ParticleGroup` for shapes such as lines, curves, polylines, polygons, spheres,
  cuboids, and text made of particles.
- `ParticleBatch` manages particle streams whose members are added and removed over time.
- `ParticleManager.emitter(anchor)` creates runtime emitters that spawn by distance or time interval.
- `Effects` registers and plays named effects with movable anchors and playback controls.
- `Animation.create { … }` builds an animation in code using the same data model as `.pdrawc`.

To bundle ParticleDrawing in your mod, use the following Gradle dependency declaration.

```kotlin
jarJar(implementation("work.nekow:particledrawing-neoforge-26.3:<version>"))
```

Older releases use `work.nekow:particledrawing:<version>`. [Multi-version layout and migration](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/multiversion.md)

## Editor playback

The editor saves projects as `.pdraw` and exports `.pdrawc` files for in-game playback. Animations can
include timeline tracks, camera tracks, OGG/WAV audio, and scripted function objects.

1. Export a `.pdrawc` file from the [ParticleDrawing editor](https://viewer.nekow.work/).
2. Put the file in `animations/` under the game directory or the dedicated server's working directory.
3. In chat, run `/pdraw animation play meteor` to play `meteor.pdrawc`.

Files are loaded on demand, so you can add or replace them while the game is running. Run
`/pdraw animation list` to list animations or `/pdraw animation stop` to stop them in the current dimension.

Use `/pdraw animation var <name> <value>` to update a script variable, `/pdraw animation debug` to check
playback status, or `/pdraw animation camera <name>` to preview a camera track in single-player.
From code, use `ServerAnimationManager` to play an animation,
update variables, or stop playback. [Animation playback](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/animation.md)

## Documentation

- [Documentation index](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/README.md)
- [Getting started](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/getting-started.md)
- [Particle commands](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/particle-commands.md)
- [API reference](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/api-reference.md)
- [Kotlin and Java examples](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/guide.md)
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

The root build targets Minecraft 26.3 and requires JDK 25. Use
`./gradlew -p platforms/neoforge-26.2 build` or `./gradlew -p platforms/neoforge-26.3 build`
to build a specific 26.x target. The Minecraft 1.21.1 target
(`./gradlew -p platforms/neoforge-1.21.1 build`) and the standalone core
(`./gradlew -p shared/core build`) target Java 21.

## Releasing

Set `mod_version` in `gradle.properties` and tag the same commit `v<mod_version>`. Pushing the tag
triggers Maven Central publication (`publish.yml`), Modrinth publication (`modrinth.yml`), and the
GitHub Release (`release.yml`). Each release workflow checks that the tag matches `mod_version`.

To publish a GitHub Release for an existing tag, open **Actions → Publish GitHub Release → Run workflow**
and enter `v<version>`. The workflow uses the built-in `GITHUB_TOKEN` with `contents: write`.
Rerunning it replaces same-named attachments and preserves the release notes.

## License

Licensed under the [Apache License 2.0](https://github.com/NekoWs/ParticleDrawing/blob/master/LICENSE).
