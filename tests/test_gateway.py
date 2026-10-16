import asyncio
import io
import json
import struct

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from pi.events import enqueue_event
from pi.protocol import ACK, COMPLETE, READY, decode_stream, encode_event, packetize, transfer_id
from pi.transfer import TransferSender
from server.app import create_app


def jpeg(color="gray"):
    buffer = io.BytesIO()
    Image.new("RGB", (64, 32), color).save(buffer, "JPEG")
    return buffer.getvalue()


def metadata():
    return {"eventId": "PI01-test-1", "type": "POTHOLE", "confidence": 0.87,
            "detectedAt": "2026-10-15T00:00:00Z", "latitude": 35.8562,
            "longitude": 128.6103, "locationAccuracy": 8.0,
            "locationAt": "2026-10-15T00:00:01Z"}


@pytest.mark.parametrize("mtu", [23, 185, 517])
def test_packet_stream_matches_mtu(mtu):
    stream = encode_event(metadata(), jpeg())
    tid = transfer_id(metadata()["eventId"])
    packets = packetize(tid, stream, mtu)
    assert all(len(packet) <= min(mtu - 3, 180) for packet in packets)
    assert [struct.unpack_from("<II", packet)[1] for packet in packets] == list(range(len(packets)))
    assert decode_stream(b"".join(packet[8:] for packet in packets)) == (metadata(), jpeg())


def test_spool_photo_and_commit_marker(tmp_path):
    event = enqueue_event(jpeg(), spool=tmp_path)
    marker = tmp_path / (event["eventId"] + ".json")
    assert json.loads(marker.read_text()) == event
    assert marker.with_suffix(".jpg").is_file()
    with pytest.raises(ValueError):
        enqueue_event(jpeg(), event_id="../bad", spool=tmp_path)


def test_sender_recovers_dropped_packet_and_lost_complete(tmp_path):
    async def scenario():
        received = bytearray()
        next_sequence = 0
        dropped = False
        final_repeats = 0
        expected = encode_event(metadata(), jpeg())
        sender = None

        def emit(packet):
            nonlocal next_sequence, dropped, final_repeats
            tid, sequence = struct.unpack_from("<II", packet)
            if sequence == 3 and not dropped:
                dropped = True
                return
            if sequence == next_sequence:
                received.extend(packet[8:])
                next_sequence += 1
            if next_sequence:
                sender.control(bytes([ACK]) + struct.pack("<II", tid, next_sequence - 1))
            if bytes(received) == expected:
                final_repeats += 1
                if final_repeats >= 2:
                    sender.control(bytes([COMPLETE]) + struct.pack("<I", tid))

        sender = TransferSender(tmp_path, emit, interval=0, timeout=0.001)
        sender.mtu = 23
        await asyncio.wait_for(sender.send_event(metadata(), jpeg()), 2)
        assert dropped and final_repeats >= 2
        assert bytes(received) == expected
    asyncio.run(scenario())


def test_disconnect_preserves_queue_until_phone_commit(tmp_path):
    async def scenario():
        event = enqueue_event(jpeg(), spool=tmp_path)
        sender = TransferSender(tmp_path, lambda packet: None, interval=0, timeout=0.001)
        sender.notifying = True
        sender.control(bytes([READY]) + struct.pack("<H", 185), actual_mtu=185)
        task = sender.task
        await asyncio.sleep(0.01)
        sender.stop()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert (tmp_path / (event["eventId"] + ".json")).exists()
        assert (tmp_path / (event["eventId"] + ".jpg")).exists()
    asyncio.run(scenario())


def test_early_complete_does_not_delete_event(tmp_path):
    sender = TransferSender(tmp_path, lambda packet: None)
    sender.tid = 12
    sender.acked = sender.highest_sent = 15
    sender.final_sequence = 80
    sender.control(bytes([COMPLETE]) + struct.pack("<I", 12))
    assert not sender.complete


def test_server_roundtrip_and_idempotent_retry(tmp_path):
    with TestClient(create_app(tmp_path, api_token="secret")) as client:
        payload = {"metadata": json.dumps(metadata())}
        files = {"image": ("scene.jpg", jpeg(), "image/jpeg")}
        assert client.post("/api/events", data=payload, files=files).status_code == 401
        headers = {"Authorization": "Bearer secret"}
        assert client.post("/api/events", data=payload, files=files, headers=headers).json()["status"] == "saved"
        assert client.post("/api/events", data=payload, files=files, headers=headers).json()["status"] == "already_saved"
        records = client.get("/api/events", headers=headers).json()
        assert len(records) == 1 and records[0]["latitude"] == metadata()["latitude"]
        assert client.get(records[0]["imageUrl"], headers=headers).content == jpeg()
        changed = {"image": ("scene.jpg", jpeg("red"), "image/jpeg")}
        assert client.post("/api/events", data=payload, files=changed, headers=headers).status_code == 409


@pytest.mark.parametrize("changes", [{"latitude": None}, {"latitude": 91}, {"confidence": 1.1},
                                      {"eventId": "../escape"}, {"detectedAt": "2026-10-15T00:00:00"}])
def test_server_rejects_invalid_event(tmp_path, changes):
    with TestClient(create_app(tmp_path, api_token="")) as client:
        response = client.post("/api/events", data={"metadata": json.dumps(metadata() | changes)},
                               files={"image": ("scene.jpg", jpeg(), "image/jpeg")})
        assert response.status_code == 422
        assert client.get("/api/events").json() == []


def test_server_rejects_invalid_and_oversized_photo(tmp_path):
    with TestClient(create_app(tmp_path, api_token="")) as client:
        for image, status in [(b"not a jpeg", 422), (b"x" * (2 * 1024 * 1024 + 1), 413)]:
            response = client.post("/api/events", data={"metadata": json.dumps(metadata())},
                                   files={"image": ("photo.jpg", image, "image/jpeg")})
            assert response.status_code == status


def test_non_ascii_authorization_returns_401_not_500(tmp_path):
    with TestClient(create_app(tmp_path, api_token="secret")) as client:
        response = client.get("/api/events", headers={"Authorization": b"Bearer caf\xe9"})
        assert response.status_code == 401


def test_non_ascii_configured_token_does_not_crash_authorization(tmp_path):
    with TestClient(create_app(tmp_path, api_token="\ud1a0\ud070")) as client:
        assert client.get("/api/events").status_code == 401


def test_bluez_service_introspection_without_hardware(tmp_path):
    from pi.ble_server import ControlCharacteristic, DataCharacteristic, GattService, ObjectManager, SERVICE
    data = DataCharacteristic()
    data.sender = TransferSender(tmp_path, data.send)
    control = ControlCharacteristic(data.sender)
    objects = {SERVICE: GattService(), SERVICE + "/data": data, SERVICE + "/control": control}
    assert ObjectManager(objects).introspect().name == "org.freedesktop.DBus.ObjectManager"
    assert any(method.name == "WriteValue" for method in control.introspect().methods)
    assert any(prop.name == "Notifying" for prop in data.introspect().properties)
