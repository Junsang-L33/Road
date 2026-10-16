import hashlib
import hmac
import io
import json
import os
import sqlite3
import warnings
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, HTMLResponse
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from pi.events import atomic_write
from pi.protocol import MAX_IMAGE_BYTES


class Event(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)
    eventId: str = Field(pattern=r"^[A-Za-z0-9_-]{1,100}$")
    type: Literal["POTHOLE", "TRAFFIC_SIGN", "LANE"]
    confidence: float = Field(ge=0, le=1)
    detectedAt: datetime
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    locationAccuracy: float = Field(ge=0)
    locationAt: datetime
    locationSource: str = "phone"
    details: dict = Field(default_factory=dict)

    @model_validator(mode="after")
    def check_timestamps(self):
        if self.detectedAt.tzinfo is None or self.locationAt.tzinfo is None:
            raise ValueError("Timestamps must include a timezone")
        return self


def create_app(data_dir=None, api_token=None):
    directory = Path(data_dir or os.getenv("ROAD_DATA_DIR", "data"))
    directory.mkdir(parents=True, exist_ok=True)
    images = directory / "images"
    images.mkdir(exist_ok=True)
    token = api_token if api_token is not None else os.getenv("ROAD_API_TOKEN", "")
    database = directory / "events.sqlite3"

    @contextmanager
    def connect():
        connection = sqlite3.connect(database, timeout=15)
        connection.row_factory = sqlite3.Row
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    with connect() as connection:
        connection.execute("CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, metadata TEXT NOT NULL, digest TEXT NOT NULL, received_at TEXT NOT NULL)")

    def authorize(authorization: Annotated[str | None, Header()] = None):
        if token and not hmac.compare_digest((authorization or "").encode(), f"Bearer {token}".encode()):
            raise HTTPException(401, "Invalid API token")

    app = FastAPI(title="Road Gateway MVP")

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.post("/api/events", dependencies=[Depends(authorize)])
    async def receive_event(metadata: Annotated[str, Form()], image: Annotated[UploadFile, File()]):
        if len(metadata.encode()) > 16384:
            raise HTTPException(413, "Metadata too large")
        try:
            event = Event.model_validate_json(metadata)
        except ValidationError as error:
            raise HTTPException(422, str(error)) from error
        jpeg = await image.read(MAX_IMAGE_BYTES + 1)
        await image.close()
        if len(jpeg) > MAX_IMAGE_BYTES:
            raise HTTPException(413, "Image exceeds 2 MiB")
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(io.BytesIO(jpeg)) as decoded:
                    if decoded.format != "JPEG" or decoded.width * decoded.height > 12_000_000:
                        raise ValueError("A JPEG of at most 12 megapixels is required")
                    decoded.load()
        except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
            raise HTTPException(422, "Invalid JPEG") from error
        canonical = event.model_dump_json()
        digest = hashlib.sha256(jpeg).hexdigest()
        with connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute("SELECT * FROM events WHERE id=?", (event.eventId,)).fetchone()
            if existing:
                if existing["digest"] != digest or existing["metadata"] != canonical:
                    raise HTTPException(409, "Event ID already contains different data")
                return {"eventId": event.eventId, "status": "already_saved"}
            atomic_write(images / f"{event.eventId}.jpg", jpeg)
            connection.execute("INSERT INTO events VALUES (?, ?, ?, ?)",
                               (event.eventId, canonical, digest, datetime.now(timezone.utc).isoformat()))
        return {"eventId": event.eventId, "status": "saved"}

    @app.get("/api/events", dependencies=[Depends(authorize)])
    def list_events(limit: int = Query(default=100, ge=1, le=1000)):
        with connect() as connection:
            rows = connection.execute("SELECT * FROM events ORDER BY received_at DESC LIMIT ?", (limit,)).fetchall()
        return [{**json.loads(row["metadata"]), "receivedAt": row["received_at"],
                 "imageUrl": f"/api/events/{row['id']}/image"} for row in rows]

    @app.get("/api/events/{event_id}/image", dependencies=[Depends(authorize)])
    def get_image(event_id: str):
        with connect() as connection:
            if not connection.execute("SELECT 1 FROM events WHERE id=?", (event_id,)).fetchone():
                raise HTTPException(404, "Event not found")
        return FileResponse(images / f"{event_id}.jpg", media_type="image/jpeg")

    @app.get("/", response_class=HTMLResponse)
    def dashboard():
        return Path(__file__).with_name("dashboard.html").read_text(encoding="utf-8")

    return app


app = create_app()
