"""Record a development Minecraft world to MP4 from an inactive Windows desktop."""

import argparse
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[2]


def positive(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError("expected a positive, finite number")
    return number


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", required=True, type=Path, help="path to a saved world folder")
    parser.add_argument("--setup", type=Path, help="commands to run before recording")
    parser.add_argument("--scene", type=Path, help="commands to run after the pre-roll")
    parser.add_argument("--output", required=True, type=Path, help="new .mp4 path")
    parser.add_argument("--platform", choices=("1.21.1", "26.2", "26.3"), default="26.3")
    parser.add_argument("--java-home", type=Path, help="Gradle JDK (21 for 1.21.1, 25 for 26.x)")
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--seconds", type=positive, default=7, help="seconds after the scene starts")
    parser.add_argument("--preroll", type=positive, default=0.5, help="seconds before the scene starts")
    parser.add_argument("--warmup", type=positive, default=3, help="settling time after setup")
    parser.add_argument("--timeout", type=positive, default=180, help="client startup timeout in seconds")
    parser.add_argument("--offline", action="store_true", help="use cached Gradle dependencies")
    args = parser.parse_args()
    if not (args.world / "level.dat").is_file():
        parser.error("--world must contain level.dat")
    if args.output.suffix.lower() != ".mp4" or args.output.exists():
        parser.error("--output must be a new .mp4 file")
    if args.output.resolve().is_relative_to(args.world.resolve()):
        parser.error("--output must be outside the source world")
    if any(size < 2 or size % 2 for size in (args.width, args.height)):
        parser.error("--width and --height must be positive even integers")
    return args


def prepare(args, session):
    profile = session / "game"
    save = profile / "saves" / "recording"
    shutil.copytree(args.world, save, ignore=shutil.ignore_patterns("session.lock"))
    (profile / "options.txt").write_text(
        'enableVsync:false\nmaxFps:260\npauseOnLostFocus:false\ninactivityFpsLimit:"minimized"\n'
        'fullscreen:false\nsoundCategory_master:0.0\nmusicToast:"never"\n',
        encoding="utf-8",
    )
    capture = session / "capture"
    capture.mkdir()
    for name in ("setup", "scene"):
        source = getattr(args, name)
        if source:
            shutil.copyfile(source, capture / f"{name}.mcfunction")
    return profile, capture


def wait_for(process, capture, marker, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        error = capture / "capture.error"
        if error.is_file():
            raise RuntimeError(error.read_text(encoding="utf-8").strip())
        if marker and (capture / marker).is_file():
            return
        code = process.poll()
        if code is not None:
            if marker is None and code == 0:
                return
            raise RuntimeError(f"Gradle exited with code {code} while waiting for {marker or 'shutdown'}")
        time.sleep(0.05)
    raise RuntimeError(f"Timed out waiting for {marker or 'shutdown'}")


def record(args, profile, capture, log):
    from hidden_desktop import launch

    environment = dict(os.environ, PD_CAPTURE_DIR=str(capture), PD_CAPTURE_AUTOMATION="1",
                       PD_CAPTURE_FPS="0", PD_RECORD_ROOT=str(ROOT), PD_RECORD_PROFILE=str(profile))
    if args.java_home:
        environment["JAVA_HOME"] = str(args.java_home.resolve())
    shell = os.environ.get("COMSPEC", r"C:\Windows\System32\cmd.exe")
    command = (
        f'{subprocess.list2cmdline([shell])} /d /v:off /s /c '
        rf'""%PD_RECORD_ROOT%\gradlew.bat" -p platforms/neoforge-{args.platform} runClient --no-daemon '
        f'--max-workers=2 "-PpdRecordProfile=%PD_RECORD_PROFILE%" -PpdQuickPlay=recording '
        f'-PpdRecordWidth={args.width} -PpdRecordHeight={args.height}'
        + (" --offline" if args.offline else "") + '"'
    )
    with launch(shell, command, ROOT, environment, log) as process:
        try:
            print("Waiting for the world on the hidden desktop...", flush=True)
            wait_for(process, capture, "capture.ready", args.timeout)
            time.sleep(args.warmup)
            (capture / "capture.start").touch()
            wait_for(process, capture, "capture.active", 30)
            print("Recording every rendered frame...", flush=True)
            if args.scene:
                time.sleep(args.preroll)
                (capture / "capture.scene").touch()
                wait_for(process, capture, "capture.scene-done", 30)
            time.sleep(args.seconds)
            (capture / "capture.stop").touch()
            wait_for(process, capture, "frames.meta", 30)
            wait_for(process, capture, None, 45)
        finally:
            if process.poll() is None and (capture / "capture.active").exists():
                (capture / "capture.stop").touch()
                deadline = time.monotonic() + 10
                while process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.05)


def timestamps(capture, count, width, height):
    times = []
    for line in (capture / "frames.index").read_text(encoding="utf-8").splitlines():
        number, stamp, frame_width, frame_height = map(int, line.split())
        if (number, frame_width, frame_height) != (len(times) + 1, width, height):
            raise RuntimeError(f"Invalid frame index: {line}")
        if (not times and stamp != 0) or (times and stamp <= times[-1]):
            raise RuntimeError(f"Invalid frame timestamp: {line}")
        times.append(stamp)
    if len(times) != count or count < 2:
        raise RuntimeError(f"Expected {count} frames, found {len(times)}")
    return times


def run_command(arguments):
    subprocess.run(arguments, check=True, creationflags=subprocess.CREATE_NO_WINDOW)


def encode(ffmpeg, ffprobe, capture, output):
    meta = dict(line.split("=", 1) for line in (capture / "frames.meta").read_text(encoding="utf-8").splitlines())
    width, height, count = (int(meta[key]) for key in ("width", "height", "frames"))
    raw = capture / "frames.raw"
    if meta["format"] != "rgba" or raw.stat().st_size != width * height * count * 4:
        raise RuntimeError("Raw video size does not match the metadata")
    times = timestamps(capture, count, width, height)
    print(f"Encoding {count} frames at {width}x{height}...", flush=True)
    pngs = capture / "images"
    pngs.mkdir()
    base = [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin"]
    run_command(base + ["-f", "rawvideo", "-pixel_format", "rgba", "-video_size", f"{width}x{height}",
                        "-i", str(raw), "-threads", "4", "-compression_level", "1", str(pngs / "%08d.png")])
    milliseconds = [0]
    for stamp in times[1:]:
        milliseconds.append(max(milliseconds[-1] + 1, (stamp + 500_000) // 1_000_000))
    manifest = pngs / "frames.ffconcat"
    with manifest.open("w", encoding="ascii", newline="\n") as entries:
        entries.write("ffconcat version 1.0\n")
        for i, stamp in enumerate(milliseconds):
            entries.write(f"file {i + 1:08}.png\noption framerate 1000\n")
            if i + 1 < count:
                entries.write(f"duration {(milliseconds[i + 1] - stamp) / 1000:.3f}\n")
    movie = capture / "recording.mp4"
    run_command(base + ["-f", "concat", "-safe", "0", "-i", str(manifest), "-fps_mode", "passthrough",
                        "-c:v", "libx264", "-enc_time_base", "1:1000", "-pix_fmt", "yuv420p", "-crf", "17",
                        "-movflags", "+faststart", str(movie)])
    encoded = subprocess.check_output([ffprobe, "-v", "error", "-select_streams", "v:0",
                                       "-show_entries", "stream=nb_frames", "-of", "csv=p=0", str(movie)],
                                      text=True, creationflags=subprocess.CREATE_NO_WINDOW).strip()
    if encoded != str(count):
        raise RuntimeError(f"Encoded {encoded} frames instead of {count}")
    movie.rename(output)
    seconds = times[-1] / 1e9
    print(f"{output}\n{count} frames, {seconds:.2f} s, {(count - 1) / seconds:.1f} measured fps", flush=True)


def main():
    if sys.platform != "win32":
        raise SystemExit("The hidden-desktop launcher requires Windows.")
    args = parse_args()
    ffmpeg, ffprobe = (shutil.which(name) for name in ("ffmpeg", "ffprobe"))
    if not ffmpeg or not ffprobe:
        raise SystemExit("ffmpeg and ffprobe must be on PATH.")
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="pd-record-", dir=args.output.parent) as directory:
        session = Path(directory)
        log = session / "client.log"
        try:
            profile, capture = prepare(args, session)
            record(args, profile, capture, log)
            encode(ffmpeg, ffprobe, capture, args.output)
        except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
            if log.is_file():
                with tempfile.NamedTemporaryFile(prefix="pd-record-", suffix=".log", dir=args.output.parent,
                                                 delete=False) as saved_log:
                    with log.open("rb") as source:
                        shutil.copyfileobj(source, saved_log)
                print(f"Client log: {saved_log.name}", file=sys.stderr)
            raise SystemExit(str(error)) from None


if __name__ == "__main__":
    main()
