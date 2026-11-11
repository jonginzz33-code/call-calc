# 계산기 작업 규칙

- **"만들어줘"라고 하기 전에는 코드를 고치거나 빌드·배포하지 않는다.** 그 전엔 질문에 답만 한다 (사용자 요청).

- 사용자는 비개발자, 한국어로 소통. README와 안내는 한국어로, 단계는 빠짐없이.
- 전체 요구 사항: 설명서 https://claude.ai/artifact/GDVqw5gHJra4RDAcQ3sNMS (v0.1 → v0.2 실시간 읽기 → v0.3 오버레이)
- 순수 로직은 `core/` (안드로이드 의존 없음, 단위 테스트 필수). 화면은 `app/` (Kotlin, View 코드로 작성).
- 버전 올릴 때: README 맨 끝에 `## vX.Y.Z` 항목 추가 (앱 버전 이름이 여기서 정해짐). main 푸시 → Actions가 테스트·빌드·릴리스(`call-calc.apk`)까지 함.
- 앱 자동 업데이트(`Updater.kt`)가 릴리스를 보고 업데이트함. 인터넷은 업데이트에만 쓰고 읽은 콜 내용은 절대 밖으로 보내지 않음.
- 새 권한이 필요하면 `PermissionGuide.all()`에 추가: 앱을 켤 때 꺼진 권한을 바로 안내하고 [켜러 가기]로 설정 화면을 연다 (사용자 요청).
  - v0.2 접근성: 사이드로드 앱은 안드로이드 13+에서 '제한된 설정 허용'(앱 정보 → ⋮)부터 해야 켜짐 → 안내 단계에 넣을 것.
- 자동 터치·자동 수락·캡처 차단 우회는 넣지 않음.
- 실시간 구조: `CallReaderService`(접근성)가 읽기+표시(`Overlay`, TYPE_ACCESSIBILITY_OVERLAY)를 모두 맡음 → '다른 앱 위에 표시' 권한 불필요. 접근성으로 못 읽으면 `CaptureService`(MediaProjection)+OCR.
- 아직 안 한 것: 고정 위치 방식(설명서 4-4 보조).
- [콜 확인]은 TMAP(SK오픈API) 장소검색 + 자동차 경로안내 (`PickupRoute.kt`, 키는 앱 설정에만 저장). 카카오 API는 쓰지 않음.
- 앱 이름은 "계산기". 패키지 이름(com.jongin.callcalc)·저장소 이름(call-calc)·APK 이름(call-calc.apk)은 자동 업데이트와 접근성 설정이 끊기지 않게 그대로 둠.
