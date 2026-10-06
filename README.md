# HAN2 Android 앱

사회적 약자를 보호하기 위한 Kotlin 기반 Android 네이티브 보안 키보드입니다.

## 기술 구성

- Kotlin 및 Android SDK
- Android `InputMethodService` 기반 보안 키보드
- 기기 내 정규식 위험 분석
- Android `SharedPreferences` 기반 설정 및 최소 감지 기록
- 향후 FastAPI 분석 서버와 HTTPS REST API 연동

웹, Electron, React, Vite, Capacitor, Cordova는 사용하지 않습니다.

## 실행

1. Android Studio에서 `android` 폴더를 엽니다.
2. Gradle 동기화를 완료합니다.
3. Android 기기 또는 에뮬레이터에서 `app` 구성을 실행합니다.
4. 앱의 안내에 따라 HAN2 키보드를 활성화하고 기본 키보드로 선택합니다.

명령줄 빌드는 `android` 폴더에서 `gradlew.bat assembleDebug`를 실행합니다.

## 앱 업데이트 서버 연결

HTTPS 주소에서 다음 형식의 JSON을 제공해야 합니다.

```json
{
  "versionCode": 8,
  "versionName": "0.5.0",
  "apkUrl": "https://example.com/han2-0.5.0.apk",
  "releaseNotes": "위험 감지 기능을 개선했습니다."
}
```

빌드할 때 업데이트 JSON 주소를 Gradle 속성으로 전달합니다.

```powershell
gradlew.bat assembleRelease -PHAN2_UPDATE_MANIFEST_URL=https://example.com/han2-update.json
```

앱은 `versionCode`를 비교하여 새 버전일 때만 업데이트 버튼을 표시합니다. APK 설치 전에는 Android 시스템 확인 화면이 표시됩니다.

업데이트 매니페스트 주소가 아직 배포되지 않은 개발 빌드는 KeyBack `/health` 서버에 연결해 업데이트 확인 서버의 접속 상태를 검증합니다. 매니페스트 주소가 설정된 배포 빌드는 위 JSON을 읽어 새 APK를 안내합니다.

## 자동 빌드·테스트·릴리즈

`main` 브랜치에 푸시하면 GitHub Actions가 다음 순서를 기본으로 실행합니다.

1. Android debug APK 빌드
2. 단위 테스트 실행
3. 앞 단계가 모두 성공한 경우 GitHub 프리릴리즈와 APK 발행

일반 `main` 푸시는 추적 가능한 `ci-<실행번호>-<커밋>` 프리릴리즈가 됩니다. 정식 버전을 발행하려면 `android/app/build.gradle`의 `versionName`과 같은 `v<version>` 태그(예: `v0.5.0`)를 푸시합니다. 태그와 앱 버전이 다르면 릴리즈 전에 실패합니다.
