# 모바일 전용 개발 원칙

이 프로젝트는 Android Studio와 Kotlin만 사용하는 Android 네이티브 앱입니다.

- 사용자 화면: Kotlin `AppCompatActivity`
- 보안 키보드: Kotlin `InputMethodService`
- 모바일 설정 데이터: Android `SharedPreferences`
- 위험 분석: 기기 내 `RiskAnalyzer`
- 기록: 원문을 제외한 위험 단계와 유형만 로컬 저장
- 서버 연동: Android에서 FastAPI HTTPS API를 직접 호출

민감 입력 원문은 기본적으로 서버나 로컬 기록에 저장하지 않습니다. 비밀번호 입력란은 분석 대상에서 제외하고, 서버 분석을 추가할 때에는 명시적인 사용자 동의와 전송 데이터 최소화 정책을 적용합니다.
