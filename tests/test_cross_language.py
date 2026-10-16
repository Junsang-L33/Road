import hashlib
import os
from pathlib import Path
import shutil
import struct
import subprocess

import pytest

from pi.protocol import encode_event, packetize, transfer_id
from test_gateway import jpeg, metadata


@pytest.mark.parametrize("mtu", [23, 185, 517])
def test_python_sender_and_actual_java_receiver(tmp_path, mtu):
    java_home = os.getenv("JAVA_HOME")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not java or not javac:
        pytest.skip("JDK 17+ is required for cross-language checks")
    root = Path(__file__).resolve().parents[1]
    source = root / "android/app/src/main/java/kr/ac/isip/roadgateway/TransferAssembler.java"
    subprocess.run([javac, "-d", str(tmp_path), str(source), str(root / "tests/TransferAssemblerCheck.java")], check=True)
    image = jpeg()
    packets = packetize(transfer_id(metadata()["eventId"]), encode_event(metadata(), image), mtu)
    fixture = tmp_path / "packets.bin"
    fixture.write_bytes(b"".join(struct.pack("<H", len(packet)) + packet for packet in packets))
    result = subprocess.run([java, "-cp", str(tmp_path), "kr.ac.isip.roadgateway.TransferAssemblerCheck", str(fixture)],
                            check=True, capture_output=True, text=True)
    assert result.stdout.strip() == hashlib.sha256(image).hexdigest()
