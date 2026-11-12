# 계산기 작업 규칙

- **"만들어줘"라고 하기 전에는 코드를 고치거나 빌드·배포하지 않는다.** 그 전엔 질문에 답만 한다 (사용자 요청).

- 사용자는 비개발자, 한국어로 소통. README와 안내는 한국어로, 단계는 빠짐없이.
- 사용자가 복사해서 붙여 넣을 값(키·주소·SHA-1 등)은 항상 따로 한 줄짜리 코드 블록(```)에 넣어 복사 버튼 한 번으로 복사되게 (사용자 요청).
- 전체 요구 사항: 설명서 https://claude.ai/artifact/GDVqw5gHJra4RDAcQ3sNMS (v0.1 → v0.2 실시간 읽기 → v0.3 오버레이)
- 순수 로직은 `core/` (안드로이드 의존 없음, 단위 테스트 필수). 화면은 `app/` (Kotlin, View 코드로 작성).
- 버전 올릴 때: README 맨 끝에 `## vX.Y.Z` 항목 추가 (앱 버전 이름이 여기서 정해짐). main 푸시 → Actions가 테스트·빌드·릴리스(`call-calc.apk`)까지 함.
- 앱 자동 업데이트(`Updater.kt`)가 릴리스를 보고 업데이트함. 인터넷은 업데이트·TMAP·서버에만 씀.
- 서버(v0.7.0, 사용자 승인): Firebase 계산기 전용 프로젝트 `call-calc-578b2` (`Cloud.kt`, 규칙은 `firestore.rules` → 콘솔에 붙여 넣기). 로그인했을 때만, **수락까지 한 콜만** 올림 (수락 창 보기·계산만 한 콜은 안 올림). 팀 알림(`teams/{코드}/shares`, 정확한 주소+화면 사진, 하루 뒤 삭제)과 내 기록(`users/{uid}/calls`, 주소는 시·구까지). 다른 앱(낚시·화장실) 프로젝트와 섞지 않음.
- v0.8.0: 모드 A 오더(`CallReaderService`)/B 뒷방(`BackroomService`, 접근성 없이 팀 알림만, 화면 켜기+알람 소리). `Store.running`+`mode`, 앱을 열어도 자동 시작 안 함. 관리자(`AdminActivity`)는 `config/admin` 문서의 uid (처음 한 번 [관리자로 등록]), 기능 허락·접근 금지는 `users/{uid}`의 features·banned → `Cloud.allowed()`. 설정은 주제 목록 + 바텀시트(`Ui.sheet`).
- 수락 감지: 사용자가 [수락]을 누른 접근성 클릭 이벤트(`isAcceptClick`). 수락 후 화면 사진을 받으면 성공/실패 확인을 추가할 것.
- 새 권한이 필요하면 `PermissionGuide.all()`에 추가: 앱을 켤 때 꺼진 권한을 바로 안내하고 [켜러 가기]로 설정 화면을 연다 (사용자 요청).
  - v0.2 접근성: 사이드로드 앱은 안드로이드 13+에서 '제한된 설정 허용'(앱 정보 → ⋮)부터 해야 켜짐 → 안내 단계에 넣을 것.
- 자동 터치·자동 수락·캡처 차단 우회는 넣지 않음.
- 실시간 구조: `CallReaderService`(접근성)가 읽기+표시(`Overlay`, TYPE_ACCESSIBILITY_OVERLAY)를 모두 맡음 → '다른 앱 위에 표시' 권한 불필요. 접근성으로 못 읽으면 `CaptureService`(MediaProjection)+OCR.
- 아직 안 한 것: 고정 위치 방식(설명서 4-4 보조).
- [콜 확인]은 TMAP(SK오픈API) 장소검색 + 자동차 경로안내 (`CallCheck.kt`, 키는 앱 설정에만 저장). 카카오 API는 쓰지 않음.
- 앱 이름은 "계산기". 패키지 이름(com.jongin.callcalc)·저장소 이름(call-calc)·APK 이름(call-calc.apk)은 자동 업데이트와 접근성 설정이 끊기지 않게 그대로 둠.
