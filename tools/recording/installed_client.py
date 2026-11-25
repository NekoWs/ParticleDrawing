"""Prepare an isolated recording profile from an installed Iris client."""

import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
from zipfile import BadZipFile, ZipFile


FEATURES = {"has_custom_resolution", "has_quick_plays_support", "is_quick_play_singleplayer"}
MOD_ID = re.compile(rb'(?m)^\s*modId\s*=\s*["\']particledrawing["\']')


def is_particle_drawing(jar):
    try:
        with ZipFile(jar) as archive:
            for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
                if name in archive.namelist() and MOD_ID.search(archive.read(name)):
                    return True
    except BadZipFile as error:
        raise ValueError(f"Invalid mod JAR: {jar}") from error
    return False


def properties_value(value):
    units = value.encode("utf-16-be")
    escaped = []
    for i in range(0, len(units), 2):
        code = int.from_bytes(units[i:i + 2], "big")
        escaped.append(chr(code) if 33 <= code < 127 and chr(code) != "\\" else f"\\u{code:04x}")
    return "".join(escaped)


def prepare_shader_profile(instance, profile, shader_pack, mod_jar):
    if not is_particle_drawing(mod_jar):
        raise ValueError("--mod-jar must be a built ParticleDrawing mod JAR")
    config = profile / "config"
    if (instance / "config").is_dir():
        shutil.copytree(instance / "config", config)
    else:
        config.mkdir()
    mods = profile / "mods"
    mods.mkdir()
    for jar in (instance / "mods").glob("*.jar"):
        if not is_particle_drawing(jar):
            shutil.copy2(jar, mods / jar.name)
    shutil.copy2(mod_jar, mods / mod_jar.name)
    for folder in (instance / (instance.name + "-natives"), instance / "natives"):
        if folder.is_dir():
            shutil.copytree(folder, profile / "natives")
            break

    source = instance / "shaderpacks" / shader_pack
    destination = profile / "shaderpacks" / shader_pack
    destination.parent.mkdir()
    if source.is_dir():
        shutil.copytree(source, destination)
    else:
        shutil.copy2(source, destination)
    overrides = source.with_name(source.name + ".txt")
    if overrides.is_file():
        shutil.copy2(overrides, destination.with_name(destination.name + ".txt"))

    iris = config / "iris.properties"
    previous = iris.read_text(encoding="latin-1").splitlines() if iris.is_file() else []
    settings = [line for line in previous if not re.match(r"\s*(enableShaders|shaderPack)(?:\s|=|:)", line)]
    settings.extend(("enableShaders=true", f"shaderPack={properties_value(shader_pack)}"))
    iris.write_text("\n".join(settings) + "\n", encoding="latin-1")


def allowed(rules):
    if not rules:
        return True
    result = False
    machine = platform.machine().lower()
    architectures = {"amd64", "x86_64"} if machine in {"amd64", "x86_64"} else {machine}
    for rule in rules:
        system = rule.get("os", {})
        if system.get("name", "windows") != "windows":
            continue
        if "arch" in system and system["arch"] not in architectures:
            continue
        if "version" in system and not re.search(system["version"], platform.version()):
            continue
        if any((key in FEATURES) != value for key, value in rule.get("features", {}).items()):
            continue
        result = rule["action"] == "allow"
    return result


def metadata_arguments(entries, values):
    for entry in entries:
        if isinstance(entry, str):
            arguments = [entry]
        elif allowed(entry.get("rules")):
            value = entry["value"]
            arguments = [value] if isinstance(value, str) else value
        else:
            continue
        for argument in arguments:
            yield re.sub(r"\$\{([^}]+)\}", lambda match: str(values[match[1]]), argument)


def library_path(libraries, item):
    artifact = item.get("downloads", {}).get("artifact")
    if artifact:
        return libraries / artifact["path"]
    group, name, version, *classifier = item["name"].split(":")
    filename = "-".join([name, version, *classifier]) + ".jar"
    return libraries.joinpath(*group.split("."), name, version, filename)


def java_args(instance, profile, width, height):
    metadata = json.loads((instance / f"{instance.name}.json").read_text(encoding="utf-8-sig"))
    if metadata.get("inheritsFrom") or not metadata.get("mainClass") or "arguments" not in metadata:
        raise ValueError("--instance requires an installed version with merged launcher metadata")
    home = instance.parent.parent
    libraries = home / "libraries"
    client_id = metadata.get("jar", instance.name)
    version_dir = instance if client_id == instance.name else instance.parent / client_id
    jars = [library_path(libraries, item) for item in metadata["libraries"] if allowed(item.get("rules"))]
    jars.append(version_dir / f"{client_id}.jar")
    missing = [str(path) for path in jars if not path.is_file()]
    if missing:
        raise ValueError(f"Missing launch file: {missing[0]} ({len(missing)} total). Launch the instance normally once.")
    asset_index = metadata.get("assetIndex", {}).get("id", metadata.get("assets"))
    assets = home / "assets"
    if not (assets / "indexes" / f"{asset_index}.json").is_file():
        raise ValueError(f"Missing installed asset index: {asset_index}")
    values = {
        "natives_directory": profile / "natives", "launcher_name": "ParticleDrawing", "launcher_version": "1",
        "classpath": os.pathsep.join(map(str, dict.fromkeys(jars))), "library_directory": libraries,
        "classpath_separator": os.pathsep, "version_name": metadata["id"], "game_directory": profile,
        "assets_root": assets, "assets_index_name": asset_index, "version_type": metadata.get("type", "release"),
        "auth_player_name": "Dev", "auth_uuid": "00000000000000000000000000000001", "auth_access_token": "0",
        "clientid": "0", "auth_xuid": "0", "user_type": "legacy", "user_properties": "{}",
        "resolution_width": width, "resolution_height": height,
        "quickPlaySingleplayer": "recording", "quickPlayPath": profile / "quick-play.json",
    }
    try:
        jvm = list(metadata_arguments(metadata["arguments"]["jvm"], values))
        game = list(metadata_arguments(metadata["arguments"]["game"], values))
    except KeyError as error:
        raise ValueError(f"Unsupported launch metadata field: {error}") from error
    if not {"--gameDir", "--width", "--height", "--quickPlaySingleplayer"}.issubset(game):
        raise ValueError("Launch metadata must support a game directory, resolution and singleplayer Quick Play")
    return ["-Dpd.backgroundCapture=true", *jvm, metadata["mainClass"], *game]


def java_launch(args, profile):
    java_home = args.java_home or os.environ.get("JAVA_HOME")
    java = str(Path(java_home).resolve() / "bin" / "java.exe") if java_home else shutil.which("java.exe")
    if not java or not Path(java).is_file():
        raise ValueError("Use --java-home to specify a JDK compatible with the installed instance")
    launch_file = profile / "recording-java.args"
    arguments = java_args(args.instance, profile, args.width, args.height)
    # Java's @file syntax interprets backslashes inside quoted arguments.
    escaped = ['"' + argument.replace("\\", "\\\\").replace('"', '\\"') + '"' for argument in arguments]
    launch_file.write_text("\n".join(escaped) + "\n", encoding="utf-8")
    return java, subprocess.list2cmdline([java, f"@{launch_file}"])
