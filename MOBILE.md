# HAN2 모바일 개발

이 프로젝트는 하나의 React/Vite 화면을 다음 런타임에서 함께 사용합니다.

- 데스크톱: Electron
- Android: Capacitor
- 웹: 일반 브라우저

## Android 준비

Android Studio와 Android SDK를 설치한 뒤 아래 명령을 실행합니다.

```bash
npm install
npm run android:open
```

`android:open`은 웹 앱을 빌드하고 Android 프로젝트에 동기화한 다음 Android Studio를 엽니다.
Android Studio에서 연결된 실제 기기 또는 에뮬레이터를 선택하고 Run을 누르면 됩니다.

웹 코드 변경 후 Android 프로젝트만 갱신하려면:

```bash
npm run mobile:sync
```

명령줄에서 연결된 기기로 실행하려면:

```bash
npm run android:run
```

기존 데스크톱 개발 방식은 그대로 유지됩니다.

```bash
npm run desktop
```

## API 주소

휴대전화에서 `localhost`는 개발 PC가 아니라 휴대전화 자신을 의미합니다.
실제 백엔드에 연결할 때 `.env.local`의 `VITE_API_BASE_URL`을 휴대전화가 접근 가능한
HTTPS 주소 또는 같은 Wi-Fi 네트워크의 개발 PC 주소로 설정해야 합니다.

## 플랫폼별 기능

- UI, API 호출, 테마와 주요 데이터 처리는 공통 코드를 사용합니다.
- Electron 자동 업데이트는 데스크톱에서만 표시됩니다.
- Android/iOS 업데이트는 각 앱 스토어가 담당합니다.
- 데스크톱 화면 공유는 Electron 구현을 유지합니다.
- 모바일 화면 공유는 Android MediaProjection 또는 iOS 화면 공유 API를 연결하는 별도 네이티브 단계가 필요합니다.
