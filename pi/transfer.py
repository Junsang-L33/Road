import asyncio
import json
import logging
from pathlib import Path

from .protocol import ACK, COMPLETE, READY, WINDOW, encode_event, packetize, transfer_id

log = logging.getLogger(__name__)


class TransferSender:
    """One phone, one event at a time, bounded windows with cumulative ACKs."""

    def __init__(self, spool, emit, interval=0.025, timeout=5.0):
        self.spool = Path(spool)
        self.spool.mkdir(parents=True, exist_ok=True)
        self.emit = emit
        self.interval = interval
        self.timeout = timeout
        self.task = None
        self.notifying = False
        self.mtu = 23
        self.tid = None
        self.acked = -1
        self.highest_sent = -1
        self.final_sequence = -1
        self.complete = False
        self.changed = asyncio.Event()

    def stop(self):
        if self.task:
            self.task.cancel()
            self.task = None
        self.tid = None

    def control(self, value, actual_mtu=23):
        import struct
        if len(value) == 3 and value[0] == READY:
            if not self.notifying:
                return
            self.stop()
            requested = struct.unpack_from("<H", value, 1)[0]
            self.mtu = min(max(actual_mtu, 23), max(requested, 23))
            self.task = asyncio.create_task(self.run())
        elif len(value) == 9 and value[0] == ACK:
            tid, sequence = struct.unpack_from("<II", value, 1)
            if tid == self.tid and self.acked < sequence <= self.highest_sent:
                self.acked = sequence
                self.changed.set()
        elif len(value) == 5 and value[0] == COMPLETE:
            tid = struct.unpack_from("<I", value, 1)[0]
            if tid == self.tid and self.acked == self.final_sequence:
                self.complete = True
                self.changed.set()

    async def wait_for_change(self):
        try:
            await asyncio.wait_for(self.changed.wait(), self.timeout)
        except asyncio.TimeoutError:
            pass
        self.changed.clear()

    async def send_event(self, metadata, image):
        self.tid = transfer_id(metadata["eventId"])
        self.acked = self.highest_sent = -1
        self.complete = False
        self.changed.clear()
        packets = packetize(self.tid, encode_event(metadata, image), self.mtu)
        self.final_sequence = len(packets) - 1
        while self.acked < len(packets) - 1:
            window_end = min(self.acked + WINDOW, len(packets) - 1)
            for sequence in range(self.acked + 1, window_end + 1):
                self.highest_sent = max(self.highest_sent, sequence)
                self.emit(packets[sequence])
                await asyncio.sleep(self.interval)
            if self.acked < window_end:
                await self.wait_for_change()
        # COMPLETE is only sent after the phone commits JPEG + metadata to disk.
        while not self.complete:
            self.emit(packets[-1])
            await self.wait_for_change()

    async def run(self):
        while True:
            for marker in sorted(self.spool.glob("*.json")):
                try:
                    metadata = json.loads(marker.read_text())
                    image_path = marker.with_suffix(".jpg")
                    await self.send_event(metadata, image_path.read_bytes())
                    marker.unlink()
                    image_path.unlink(missing_ok=True)
                    log.info("Phone received %s", metadata["eventId"])
                except asyncio.CancelledError:
                    raise
                except Exception:
                    log.exception("Queue event failed: %s", marker)
                    await asyncio.sleep(2)
            await asyncio.sleep(0.5)
