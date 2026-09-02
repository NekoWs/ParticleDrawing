# ParticleDrawing
A high-performance particle animation player, and supports custom textures and other features.

## New Additions
- `/pdraw` command to play or stop particle animations

## Features
- API calls
- Play .pdraw particle animations
- Build & play animations entirely from code (`Animation.create`)

## Why Choose ParticleDrawing
This mod can play 50,000+ animated particle animations with a stable frame rate.

## How to Use
### For Players
You can visit https://viewer.nekow.work/ to create your own particle animations.

Place the exported particle animation in the `animations` folder under the game directory.

Use `/pdraw play <filename>` in-game to play your animation, **no need to reload the game**.

### For Developers
Add the dependency in your mod's build.gradle:
```gradle
dependencies {
    // ...
    compileOnly "work.nekow:particledrawing:1.0.3"
    localRuntime "work.nekow:particledrawing:1.0.3"
}
```
The API development is still being gradually improved. Welcome to visit [Github](https://github.com/NekoWs/ParticleDrawing) to submit PRs!