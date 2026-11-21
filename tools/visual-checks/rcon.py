import socket
import struct
import sys
from pathlib import Path

def send(connection, serial, kind, message):
    payload = struct.pack("<ii", serial, kind) + message.encode("utf-8") + b"\0\0"
    connection.sendall(struct.pack("<i", len(payload)) + payload)

def receive(stream):
    length, = struct.unpack("<i", stream.read(4))
    packet = stream.read(length)
    serial, kind = struct.unpack_from("<ii", packet)
    return serial, kind, packet[8:-2].decode("utf-8")

with socket.create_connection(("127.0.0.1", 25782), timeout=10) as connection:
    with connection.makefile("rb") as stream:
        send(connection, 10, 3, "pd-local-acceptance-only")
        serial, kind, _ = receive(stream)
        if serial != 10 or kind != 2:
            raise RuntimeError("Local acceptance RCON rejected")
        commands = Path(sys.argv[2]).read_text(encoding="utf-8").splitlines() if sys.argv[1] == "--file" else sys.argv[1:]
        for number, command in enumerate(commands, 11):
            if not command.strip():
                continue
            send(connection, number, 2, command)
            _, _, output = receive(stream)
            print(command, " -> ", output)