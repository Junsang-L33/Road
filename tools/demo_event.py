"""Generate a clearly labelled synthetic photo to exercise BLE without YOLO."""

import argparse
import json

from PIL import Image, ImageDraw

from pi.events import enqueue_event


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--spool", default="spool")
    args = parser.parse_args()
    scene = Image.new("RGB", (1280, 720), "#535a60")
    draw = ImageDraw.Draw(scene)
    draw.polygon([(370, 720), (605, 0), (630, 0), (425, 720)], fill="#eeeece")
    draw.polygon([(855, 720), (650, 0), (675, 0), (910, 720)], fill="#eeeece")
    draw.ellipse((500, 480, 800, 640), fill="#20272d", outline="#a09d8a", width=15)
    draw.rectangle((20, 20, 570, 80), fill="black")
    draw.text((40, 40), "SYNTHETIC DEMO - NOT A REAL DETECTION", fill="white")
    event = enqueue_event(scene, confidence=0.87, spool=args.spool, details={"synthetic": True})
    print(json.dumps(event, indent=2))


if __name__ == "__main__":
    main()
