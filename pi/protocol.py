"""Shared wire format; see docs/protocol.md and Android TransferAssembler."""

import json
import struct
import zlib

SERVICE_UUID = "7d53a100-5f67-4fba-9fb5-8e5d73f91801"
DATA_UUID = "7d53a101-5f67-4fba-9fb5-8e5d73f91801"
CONTROL_UUID = "7d53a102-5f67-4fba-9fb5-8e5d73f91801"
READY, ACK, COMPLETE = 1, 2, 3
WINDOW = 16
MAX_IMAGE_BYTES = 2 * 1024 * 1024
MAX_META_BYTES = 8192


def encode_event(metadata, image):
    encoded = json.dumps(metadata, ensure_ascii=False, separators=(",", ":")).encode()
    if not 0 < len(encoded) <= MAX_META_BYTES:
        raise ValueError("Invalid metadata size")
    if not 0 < len(image) <= MAX_IMAGE_BYTES:
        raise ValueError("Invalid image size")
    return struct.pack("<II", len(encoded), len(image)) + encoded + image


def transfer_id(event_id):
    return zlib.crc32(event_id.encode())


def packetize(tid, stream, mtu):
    # Cap ATT values at 180 bytes even if Android negotiates MTU 517.
    payload = min(max(mtu, 23) - 3, 180) - 8
    return [struct.pack("<II", tid, seq) + stream[offset:offset + payload]
            for seq, offset in enumerate(range(0, len(stream), payload))]


def decode_stream(stream):
    if len(stream) < 8:
        raise ValueError("Missing length header")
    meta_size, image_size = struct.unpack_from("<II", stream)
    if not 0 < meta_size <= MAX_META_BYTES or not 0 < image_size <= MAX_IMAGE_BYTES:
        raise ValueError("Invalid lengths")
    if len(stream) != 8 + meta_size + image_size:
        raise ValueError("Incomplete stream")
    return json.loads(stream[8:8 + meta_size]), stream[8 + meta_size:]
