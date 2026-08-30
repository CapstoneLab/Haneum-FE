# KeyBack API 인증 오류 해결 가이드

## 현재 상태

- Android 프로젝트의 단위 테스트가 통과했습니다.
- Debug APK 빌드에 성공했습니다.
- Android 앱에서 KeyBack 백엔드 서버까지 네트워크 연결은 정상입니다.
- 유효한 Android API 키 적용 후 백엔드의 `/api/v1/health` 요청이 HTTP `200`으로 성공했습니다.
- 백엔드, 데이터베이스 및 LLM 상태가 모두 `up`으로 확인되었습니다.

초기 `401 UNAUTHORIZED` 오류는 자리표시자 키를 실제 인증 키로 교체하여 해결했습니다. 다른 환경에서도 백엔드와 Android 앱에 동일한 실제 인증 키를 설정해야 합니다.

## 핵심 해결 조건

```text
백엔드의 ANDROID_API_KEY = Android 앱의 KEYBACK_API_KEY
```

두 값은 공백과 대소문자를 포함하여 완전히 같아야 합니다.

## 전체 비밀키 구성도

각 키의 용도와 저장 위치는 다음과 같습니다.

| 키 | 생성/발급 주체 | 백엔드 `.env` | Android `local.properties` | 두 위치의 값이 같아야 하는가? |
|---|---|---:|---:|---:|
| `ANDROID_API_KEY` | 직접 생성 | O | `KEYBACK_API_KEY`라는 이름으로 O | O |
| `INTERNAL_API_KEY` | 직접 생성 | O | X | X |
| `ANTHROPIC_API_KEY` | Anthropic Console | O | X | 해당 없음 |

```text
Android 앱
  KEYBACK_API_KEY ───────────────┐
                                │ 완전히 동일한 값
백엔드 서버                     │
  ANDROID_API_KEY ◀─────────────┘
  INTERNAL_API_KEY    (백엔드 내부 호출 전용)
  ANTHROPIC_API_KEY   (Claude 호출 전용)
```

## 1. Android 인증 키 생성

PowerShell에서 다음 명령을 실행합니다.

```powershell
[guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")
```

출력된 64자 문자열을 안전한 곳에 보관합니다. 이 키를 공개 채팅, 문서 또는 Git 저장소에 올리지 마십시오.

Android 키와 내부 키를 한 번에 생성하려면 다음 명령을 사용할 수 있습니다.

```powershell
$keybackAndroidKey = [guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")
$keybackInternalKey = [guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")

"ANDROID_API_KEY=$keybackAndroidKey"
"INTERNAL_API_KEY=$keybackInternalKey"
```

출력 예시는 다음과 같습니다. 아래 값은 설명용 예시이며 실제 키로 사용하지 마십시오.

```env
ANDROID_API_KEY=EXAMPLE_ONLY_REPLACE_WITH_GENERATED_64_CHARACTER_ANDROID_KEY
INTERNAL_API_KEY=EXAMPLE_ONLY_REPLACE_WITH_DIFFERENT_64_CHARACTER_INTERNAL_KEY
```

## 2. 백엔드 환경변수 설정

백엔드 프로젝트의 `.env` 파일에서 다음 값을 설정합니다.

```env
ANDROID_API_KEY=<생성한_64자_문자열>
```

내부 API 키는 Android 인증 키와 다른 값으로 생성합니다.

```env
INTERNAL_API_KEY=<별도로_생성한_64자_문자열>
```

Anthropic API 키는 백엔드에만 설정합니다.

```env
ANTHROPIC_API_KEY=<새로_발급한_Anthropic_API_키>
```

> 이미 공개된 Anthropic API 키는 폐기하고 새로 발급해야 합니다. Anthropic 키를 Android 앱이나 APK에 포함하면 안 됩니다.

### 백엔드 `.env` 전체 예시

다음 내용을 백엔드의 `.env` 파일 형식으로 사용할 수 있습니다. 꺾쇠괄호로 표시된 값은 실제 값으로 교체합니다.

```env
APP_ENV=development
APP_NAME=KeyBack API
API_V1_PREFIX=/api/v1

# Android 앱 인증용: Android의 KEYBACK_API_KEY에도 같은 값을 입력합니다.
ANDROID_API_KEY=<직접_생성한_64자_ANDROID_키>

# 백엔드 내부 API 호출용: ANDROID_API_KEY와 다른 값을 사용합니다.
INTERNAL_API_KEY=<직접_생성한_별도의_64자_내부_키>

# PostgreSQL 접속 정보
DATABASE_URL=postgresql+psycopg://keyback:keyback@postgres:5432/keyback

# LLM 설정
LLM_PROVIDER=claude
LLM_BASE_URL=http://host.docker.internal:8001/internal/v1
LLM_TIMEOUT_SECONDS=8.0

# Anthropic Console에서 새로 발급한 키: 백엔드에만 저장합니다.
ANTHROPIC_API_KEY=<새로_발급한_sk-ant-api03_키>
```

> `ANTHROPIC_BASE_URL=https\://`처럼 불완전한 값은 사용하지 않습니다. 백엔드 구현에서 이 변수가 필수가 아니라면 제거하고, 필수라면 해당 백엔드 문서가 요구하는 완전한 URL을 설정합니다.

### 값 입력 예시

아래는 값이 어느 위치에 들어가는지 보여주는 형식 예시입니다. 실제 운영에는 이 예시 문자열을 사용하지 마십시오.

```env
ANDROID_API_KEY=EXAMPLE_ANDROID_KEY_DO_NOT_USE
INTERNAL_API_KEY=EXAMPLE_INTERNAL_KEY_DO_NOT_USE
ANTHROPIC_API_KEY=sk-ant-EXAMPLE-REVOKED-KEY-DO-NOT-USE
```

## 3. 백엔드 재시작

Docker Compose를 사용한다면 백엔드 프로젝트 폴더에서 실행합니다.

```powershell
docker compose up -d --force-recreate
```

환경변수 변경 사항을 반영하려면 컨테이너를 재생성해야 합니다.

## 4. Android 앱 설정

`android/local.properties` 파일을 다음 형식으로 설정합니다.

```properties
sdk.dir=C\:\\Users\\User\\AppData\\Local\\Android\\Sdk
KEYBACK_BASE_URL=http\://112.186.136.153\:8000/api/v1
KEYBACK_API_KEY=<백엔드의_ANDROID_API_KEY와_동일한_값>
```

예를 들어 백엔드 설정이 다음과 같다면,

```env
ANDROID_API_KEY=abc123_example_only
```

Android 설정은 반드시 다음과 같아야 합니다.

```properties
KEYBACK_API_KEY=abc123_example_only
```

변수 이름은 서로 다르지만 값은 동일합니다.

`local.properties`는 Git에 커밋하지 않습니다. 다음 값들은 Android 프로젝트에 넣지 않습니다.

- `ANTHROPIC_API_KEY`
- `INTERNAL_API_KEY`
- `DATABASE_URL`

## 5. API 인증 확인

PowerShell에서 다음 명령을 실행합니다.

```powershell
curl.exe --header "X-API-Key: <ANDROID_API_KEY>" `
  http://112.186.136.153:8000/api/v1/health
```

정상 상태라면 `401 UNAUTHORIZED` 대신 HTTP `200` 성공 응답이 반환되어야 합니다.

계속 `401`이 반환된다면 다음 항목을 확인합니다.

1. 백엔드의 `ANDROID_API_KEY`와 앱의 `KEYBACK_API_KEY`가 정확히 같은지 확인합니다.
2. `.env` 수정 후 백엔드 컨테이너를 재생성했는지 확인합니다.
3. 백엔드가 올바른 `.env` 파일을 읽고 있는지 확인합니다.
4. 키 앞뒤에 불필요한 공백이나 따옴표가 없는지 확인합니다.

## 6. Android 앱 재빌드

```powershell
cd C:\Users\User\Desktop\Haneum-FE\android
.\gradlew.bat clean testDebugUnitTest assembleDebug
```

빌드가 완료되면 APK는 다음 위치에 생성됩니다.

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## 보안 주의사항

- 실제 API 키를 채팅이나 공유 문서에 작성하지 않습니다.
- `.env`와 `local.properties`를 Git에 커밋하지 않습니다.
- Anthropic API 키는 백엔드에서만 사용합니다.
- Android APK에 포함된 인증 키는 추출될 수 있으므로 운영 환경에서는 키 교체, 기기별 인증 또는 단기 토큰 방식을 고려해야 합니다.
- 테스트가 끝나면 임시로 사용한 모든 인증 키를 교체합니다.
