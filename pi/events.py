import argparse
import io
import json
import math
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

from PIL import Image

EVENT_TYPES = {"POTHOLE", "TRAFFIC_SIGN", "LANE"}
SAFE_ID = re.compile(r"^[A-Za-z0-9_-]{1,100}$")


def atomic_write(path, content):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(dir=path.parent, prefix=".pending-")
    try:
        with os.fdopen(fd, "wb") as file:
            file.write(content)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def enqueue_event(image, event_type="POTHOLE", confidence=0.5,
                  spool="spool", event_id=None, detected_at=None, details=None):
    """Call from any detector. image is a PIL Image, path, or JPEG bytes."""
    if event_type not in EVENT_TYPES or not math.isfinite(confidence) or not 0 <= confidence <= 1:
        raise ValueError("Invalid event type or confidence")
    event_id = event_id or f"PI01-{uuid4().hex}"
    if not SAFE_ID.fullmatch(event_id):
        raise ValueError("Invalid event ID")
    if not isinstance(image, Image.Image):
        image = Image.open(io.BytesIO(image) if isinstance(image, bytes) else image)
    image = image.convert("RGB")
    image.thumbnail((1280, 720))
    buffer = io.BytesIO()
    image.save(buffer, "JPEG", quality=75)
    jpeg = buffer.getvalue()
    metadata = {
        "eventId": event_id, "type": event_type, "confidence": confidence,
        "detectedAt": detected_at or datetime.now(timezone.utc).isoformat(),
        "imageBytes": len(jpeg), "details": details or {},
    }
    directory = Path(spool)
    # The JSON file is the queue commit marker. The sender never sees half a JPEG.
    atomic_write(directory / f"{event_id}.jpg", jpeg)
    atomic_write(directory / f"{event_id}.json", json.dumps(metadata, ensure_ascii=False).encode())
    return metadata


def main():
    parser = argparse.ArgumentParser(description="Queue a photo for BLE transfer")
    parser.add_argument("--image", required=True)
    parser.add_argument("--type", choices=sorted(EVENT_TYPES), default="POTHOLE")
    parser.add_argument("--confidence", type=float, default=0.5)
    parser.add_argument("--spool", default="spool")
    args = parser.parse_args()
    print(json.dumps(enqueue_event(args.image, args.type, args.confidence, args.spool), indent=2))


if __name__ == "__main__":
    main()
