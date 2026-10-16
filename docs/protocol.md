# BLE 통신 규격 v1

Pi는 BLE Peripheral / GATT 서버, Android 앱은 Central / GATT 클라이언트다.
시연용 단일 휴대폰 연결을 전제로 한다. 두 코드의 UUID를 함께 변경해야 한다.

| 항목 | UUID | 기능 |
| --- | --- | --- |
| 서비스 | `7d53a100-5f67-4fba-9fb5-8e5d73f91801` | RoadPi 검색 필터 |
| DATA | `7d53a101-5f67-4fba-9fb5-8e5d73f91801` | Notify, 이벤트/사진 조각 |
| CONTROL | `7d53a102-5f67-4fba-9fb5-8e5d73f91801` | Write with response, 수신 확인 |

모든 정수는 little-endian이다.

## 이벤트 스트림

```text
[metadata 길이 uint32][JPEG 길이 uint32][UTF-8 JSON][JPEG 원본 바이트]
```

metadata는 최대 8 KiB, JPEG는 최대 2 MiB다. 예시:

```json
{
  "eventId": "PI01-고유ID",
  "type": "POTHOLE",
  "confidence": 0.87,
  "detectedAt": "2026-10-15T00:00:00+00:00",
  "imageBytes": 120000,
  "details": {"boxes": [[100, 200, 300, 400]]}
}
```

## DATA 패킷

```text
[transfer ID uint32][sequence uint32][스트림 일부]
```

transfer ID는 `eventId`의 CRC32이며, 파일 무결성 해시 용도가 아니다.
sequence는 이벤트마다 0부터 시작한다. ATT 값 길이는 `min(MTU - 3, 180)`이다.
기본 MTU 23에서도 전송 가능하며 앱은 185를 요청한다.
Pi는 16패킷씩 보내고 ACK를 기다린다. 전송 간격 기본 25ms, ACK 시간 제한 5초다.

## CONTROL 명령

| 명령 | 바이트 구조 | 의미 |
| --- | --- | --- |
| READY | `0x01 + MTU uint16` | Notify 활성화 후 대기 이벤트 전송 시작 |
| ACK | `0x02 + transfer ID uint32 + sequence uint32` | 연속 수신한 마지막 순번 |
| COMPLETE | `0x03 + transfer ID uint32` | 휴대폰 파일 저장 완료 |

앱은 중복 패킷을 무시하고 누락 이후 패킷을 연결하지 않는다.
Pi는 ACK가 없는 나머지 구간을 재전송한다. 최종 ACK만으로 Pi 사진을 삭제하지 않고,
COMPLETE까지 받아야 삭제한다. COMPLETE가 누락되면 마지막 패킷을 다시 보내 확인한다.
연결이 끊기면 다음 연결에서 해당 이벤트를 처음부터 다시 전송한다.
앱은 `eventId`로 이미 저장한 이벤트를 확인하여 최초 GPS와 업로드 상태를 유지한다.

## 서버 API

`POST /api/events`는 multipart/form-data로 전송한다.

| 필드 | 내용 |
| --- | --- |
| `metadata` | 이벤트 JSON 문자열 + 휴대폰 위치 필드 |
| `image` | `image/jpeg` 파일 |

위치 필드는 `latitude`, `longitude`, `locationAccuracy`(m), `locationAt`(시간대 포함 ISO 8601),
`locationSource: phone`이다. 앱은 첫 사진 패킷 수신 시 최신 위치를 고정한다.
동일 eventId와 동일 데이터의 재전송은 `already_saved`로 성공 처리한다.
같은 ID에 다른 사진/메타데이터가 오면 HTTP 409를 반환한다.

서버 응답은 `{"eventId":"...","status":"saved"}` 또는 `already_saved`다.
앱은 HTTP 성공 코드와 eventId, status까지 확인한 다음 업로드 완료로 기록한다.
