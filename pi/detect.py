import argparse
import time
from pathlib import Path

from .events import enqueue_event


def main():
    parser = argparse.ArgumentParser(description="YOLO pothole detection -> persistent BLE queue")
    parser.add_argument("--weights", required=True, help="Your pothole best.pt or best.onnx")
    parser.add_argument("--source", default="pi", help="pi, webcam index, or a video file")
    parser.add_argument("--spool", default="spool")
    parser.add_argument("--conf", type=float, default=0.5)
    parser.add_argument("--cooldown", type=float, default=10.0)
    parser.add_argument("--class-ids", default="0", help="Pothole class IDs, e.g. 0 or 0,1")
    args = parser.parse_args()
    if not Path(args.weights).is_file():
        parser.error("Weights file does not exist. Use your trained pothole weights.")
    if not 0 < args.conf <= 1 or args.cooldown < 0:
        parser.error("Invalid confidence or cooldown")
    import cv2
    from PIL import Image
    from ultralytics import YOLO

    classes = [int(value) for value in args.class_ids.split(",")]
    model = YOLO(args.weights, task="detect")
    camera = None
    capture = None
    if args.source == "pi":
        from picamera2 import Picamera2
        camera = Picamera2()
        # Picamera2 RGB888 arrays are BGR byte order, matching OpenCV/YOLO.
        camera.configure(camera.create_video_configuration(main={"size": (1280, 720), "format": "RGB888"}))
        camera.start()
    else:
        capture = cv2.VideoCapture(int(args.source) if args.source.isdigit() else args.source)
        if not capture.isOpened():
            raise RuntimeError("Could not open camera/video")
    last_event = float("-inf")
    try:
        while True:
            if camera:
                frame = camera.capture_array()
            else:
                success, frame = capture.read()
                if not success:
                    break
            result = model.predict(frame, conf=args.conf, classes=classes, imgsz=640,
                                   device="cpu", verbose=False)[0]
            if result.boxes is not None and len(result.boxes) and time.monotonic() - last_event >= args.cooldown:
                # Send the original scene; boxes are metadata for later overlays.
                event = enqueue_event(
                    Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)),
                    confidence=float(result.boxes.conf.max()), spool=args.spool,
                    details={"boxes": result.boxes.xyxy.cpu().tolist(),
                             "classes": result.boxes.cls.cpu().tolist()},
                )
                last_event = time.monotonic()
                print("Queued", event["eventId"], "confidence", event["confidence"], flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        if camera:
            camera.stop()
            camera.close()
        if capture:
            capture.release()


if __name__ == "__main__":
    main()
