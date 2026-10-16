# 도로 감지 모바일 게이트웨이 MVP

```text
Pi 카메라 + 포트홀 YOLO → JPEG 저장 → BLE → Android 앱 + 휴대폰 GPS → 서버
```

설계 문서에 맞춘 실행 가능한 초기 구현이다. Android 앱은 실제로 빌드한 디버그 APK를 제공한다.
포트홀 모델 파일은 기존에 학습한 `best.pt` 또는 `best.onnx`를 별도로 넣는다.
표지판/차선 모델은 아래 `enqueue_event()`를 연결하여 같은 전송 흐름을 사용한다.

## 1. 가장 먼저 해볼 것

1. PC에서 아래 테스트 서버를 실행한다.
2. 아래 `ServerConfig.java`에 서버 주소를 기입한 뒤 앱을 빌드한다.
3. Android 휴대폰에 APK를 설치한다. 휴대폰과 PC는 같은 Wi-Fi에 둔다.
4. Pi에 이 프로젝트 폴더를 복사하고 BLE 서버를 실행한다.
5. 휴대폰의 블루투스·위치를 켜고 앱에서 권한을 허용한다.
6. 앱에서 BLE 수신을 시작하고 GPS 좌표가 표시될 때까지 기다린다.
7. Pi에서 테스트 사진 이벤트를 발생시킨다. 앱과 PC 브라우저에서 저장 결과를 확인한다.

통신 확인 이후 포트홀 모델과 실제 카메라를 연결한다.

## 2. PC 또는 서버

프로젝트 루트(`road_gateway`)에서 실행한다.

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python -m uvicorn server.app:app --host 0.0.0.0 --port 8000
```

PC 브라우저에서 `http://localhost:8000`은 사진/좌표 조회 화면,
`http://localhost:8000/docs`는 API 문서다. 위치 링크로 지도에서 좌표를 볼 수 있다.
앱 코드의 `ServerConfig.BASE_URL`에는 `localhost`가 아니라 PC의 LAN IP를 넣는다. 예: `http://192.168.0.20:8000`.
PC 방화벽에서 8000 포트가 허용되어야 한다.

사진은 `data/images/`, 기록은 `data/events.sqlite3`에 저장된다.
재실행해도 기록이 유지된다. 서버 경로는 `ROAD_DATA_DIR` 환경변수로 변경할 수 있다.

선택적으로 업로드/조회 토큰을 설정한다. 앱 코드의 `ServerConfig.API_TOKEN`에 같은 값을 지정하고,
서버 조회 화면에서도 같은 토큰을 입력한다.

```bash
export ROAD_API_TOKEN='change-me-local-demo'
python -m uvicorn server.app:app --host 0.0.0.0 --port 8000
```

팀의 기존 서버를 사용하려면 [통신 규격](docs/protocol.md)의 multipart API를 구현하거나
Android `Uploader.java`의 주소/필드를 기존 API에 맞춘다.
외부 서버는 HTTPS 주소를 사용한다. 제공한 디버그 APK는 같은 Wi-Fi에서 HTTP 테스트도 가능하다.

## 3. Raspberry Pi 5

Raspberry Pi OS 64비트 기준. 프로젝트 폴더를 Pi 홈 디렉토리에 복사한 뒤 이동한다.

```bash
sudo apt update
sudo apt install -y bluez python3-venv python3-picamera2 python3-opencv
sudo systemctl enable --now bluetooth
sudo rfkill unblock bluetooth
python3 -m venv --system-site-packages .venv
source .venv/bin/activate
pip install -r pi/requirements.txt
mkdir -p spool models
sudo .venv/bin/python -m pi.ble_server --spool "$PWD/spool"
```

`--system-site-packages`는 apt로 설치한 Picamera2를 가상환경에서 사용하기 위한 설정이다.
BLE 서버는 Linux BlueZ를 사용하므로 Mac에서 실행하는 프로그램이 아니다.
Pi 터미널에 `RoadPi ready`가 표시되면 Android 앱에서 BLE 수신을 시작한다.
여기서는 한 대의 Pi와 한 대의 휴대폰을 연결한다. OS 설정의 블루투스 페어링 대신 앱에서 검색한다.

다른 Pi 터미널에서 모델 없이 전송을 확인한다.

```bash
cd ~/road_gateway
source .venv/bin/activate
python -m tools.demo_event --spool "$PWD/spool"
```

테스트 그림은 `SYNTHETIC DEMO`라고 표시되며 실제 감지 결과가 아니다.
가지고 있는 실제 JPEG로도 테스트할 수 있다.

```bash
python -m pi.events --image ./test.jpg --type POTHOLE --confidence 0.87 --spool "$PWD/spool"
```

사진은 `spool/`에 보관되며, 휴대폰이 파일 저장 완료를 알려주면 Pi에서 제거된다.
블루투스 연결이 끊겨도 대기 중인 사진은 남는다. 두 터미널의 `--spool` 경로를 같게 지정한다.
휴대폰은 재전송 시 메타데이터와 실제 사진 파일을 함께 확인하며, 사진 파일이 누락/손상됐으면 복원한 뒤 완료를 알린다.

## 4. 포트홀 모델 + 실제 카메라

학습한 가중치를 `models/pothole.pt`에 넣고 BLE 서버가 실행 중인 상태에서 실행한다.

```bash
python -m pi.detect --weights models/pothole.pt --source pi --conf 0.5 --spool "$PWD/spool"
```

기존 ONNX 모델도 연결할 수 있다. 모델 실행 환경에서 필요하면 `pip install onnxruntime`을 실행한다.

```bash
python -m pi.detect --weights models/pothole.onnx --source pi --conf 0.5 --spool "$PWD/spool"
```

`--conf 0.5`가 감지 기준이다. 기본 입력 640, CPU 실행, 같은 장면의 반복 신고를 줄이기 위한
이벤트 간격은 기본 10초다. `--cooldown 5` 등으로 변경한다.
단일 포트홀 학습 모델의 클래스 0을 기본으로 사용하며 다중 클래스라면 `--class-ids`를 지정한다.
사진은 원본 장면을 JPEG 품질 75로 저장하고, 최대 1280×720 안에 비율을 유지해 축소한다.

USB 카메라 또는 영상으로 시험할 수 있다.

```bash
python -m pi.detect --weights models/pothole.pt --source 0 --conf 0.5
python -m pi.detect --weights models/pothole.pt --source ./drive.mp4 --conf 0.5
```

Pi 5의 실제 추론 속도는 모델/환경에 따라 측정해야 한다.
현재 코드는 카메라 1개와 포트홀 모델을 연결한다. 표지판과 차선의 학습/추론 코드는 팀원의 것을 연결한다.

## 5. 팀원 모델 연결

Pi 쪽 감지 코드에서 PIL 이미지 또는 JPEG 바이트/파일 경로를 넘긴다.

```python
from pi.events import enqueue_event

enqueue_event(
    image="sign.jpg",
    event_type="TRAFFIC_SIGN",  # POTHOLE / TRAFFIC_SIGN / LANE
    confidence=0.92,
    spool="/home/사용자/road_gateway/spool",
    details={"signType": "STOP"},
)
```

OpenCV 프레임은 아래처럼 변환한다. 모델마다 매 프레임 전송하지 않고 신고할 이벤트만 넣는다.

```python
import cv2
from PIL import Image

enqueue_event(Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)),
              event_type="LANE", confidence=0.9, spool="spool")
```

## 6. Android 앱

서버 주소는 앱 사용자에게 입력받지 않고 다음 파일에서 지정한다.

`android/app/src/main/java/kr/ac/isip/roadgateway/ServerConfig.java`

```java
public static final String BASE_URL = "";
public static final String API_TOKEN = "";
```

현재 서버 주소가 미정이므로 두 값은 비워 두었다. 주소가 비어 있으면 BLE 수신과 사진 저장은
가능하지만 서버 업로드는 대기한다. 주소가 정해지면 `BASE_URL`에 기본 주소만 넣는다.
예: `https://api.example.com` 또는 로컬 테스트용 `http://192.168.0.20:8000`.
`/api/events`는 앱이 붙이므로 주소에 추가하지 않는다.
서버가 토큰을 요구할 때만 `API_TOKEN`도 기입한다.
설정값 변경 후 앱을 다시 빌드·설치한다. 같은 앱을 업데이트하면 보관 중인 사진이 유지되고,
주소와 GPS가 있는 미전송 사진은 자동 업로드 대상이 된다.

`android/`를 Android Studio에서 열어 빌드한다. JDK 17+, Android SDK 34 기준이다.

```bash
cd android
./gradlew assembleDebug
```

APK 원본 경로는 `app/build/outputs/apk/debug/app-debug.apk`다.
앱 기능: 코드에 지정한 서버 사용, BLE 자동 재연결, 사진 복원/내부 저장, GPS 결합, 서버 업로드,
30초 간격 업로드 재시도, 최근 감지 기록 및 사진 표시.
사진 저장과 서버 업로드는 별도 작업 큐에서 실행하므로 서버 응답 지연이 새 사진 저장을 막지 않는다.
손상된 기록과 개별 서버 거절(409/413/422)은 다른 기록의 업로드를 막지 않으며, 해당 파일은 삭제하지 않는다.

이번 MVP는 앱을 화면에 열어 둔 상태로 시연한다. 화면 꺼짐을 방지한다.
다른 앱으로 전환하면 BLE 수신이 멈추고, 돌아오면 다시 연결한다.
백그라운드 상시 동작이 필요해지면 foreground service를 추가한다.

GPS는 긴 사진 전송이 끝난 뒤가 아니라 **첫 패킷 수신 시점**의 최신 위치를 사용한다.
30초보다 오래된 위치는 사용하지 않는다. 위치를 못 얻은 이벤트는 사진만 휴대폰에 보관하고
서버에 자동 전송하지 않는다. 시연 전에 좌표가 표시되는지 확인한다.
Pi에 오래 대기했다가 나중에 전달된 이벤트의 위치는 실제 감지 위치와 다를 수 있으므로,
이번 시연은 Pi-휴대폰 연결 및 GPS가 준비된 상태에서 진행한다.

## 7. 검증

```bash
source .venv/bin/activate
python -m pytest -q
cd android
./gradlew assembleDebug lintDebug
```

JDK 17+가 있으면 Python 송신 패킷을 실제 Java 수신 코드로 복원하는 테스트도 실행한다.
테스트는 MTU 23/185/517, 패킷 누락/중복, COMPLETE 재전송, 연결 중단 시 Pi 큐 보존,
서버 사진/좌표 저장, 중복 재전송, 잘못된 입력과 사진을 검사한다.
Pi의 BlueZ·실제 카메라·휴대폰 BLE/GPS는 위 시연 절차로 실제 장치에서 확인해야 한다.

Android 에뮬레이터 회귀 테스트도 포함한다. `adb`가 설치되고 에뮬레이터가 실행 중인 상태에서
`android/` 디렉토리에서 실행한다. 테스트는 임시 저장소를 사용한다.

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w kr.ac.isip.roadgateway.debug.test/kr.ac.isip.roadgateway.GatewayRegressionInstrumentation
```

성공 출력은 `passed=6`, `failed=0`이다. 사진 누락/손상 복원, 기존 GPS와 업로드 상태 유지,
동일 ID에 다른 사진 거절, 손상된 기록 분리, 업로드 지연 중 사진 저장,
서버 설정 입력란이 없는 화면을 확인한다.

## 참고

- [BlueZ GATT API](https://bluez.readthedocs.io/en/latest/gatt-api/)
- [Android BluetoothGatt](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)
- [FastAPI 파일 수신](https://fastapi.tiangolo.com/tutorial/request-files/)
- [Ultralytics 추론 API](https://docs.ultralytics.com/modes/predict/)
