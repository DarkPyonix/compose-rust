창 크기를 끄는 동안 "그릴 수 있을 때까지 창의 틀이 오지 않게" 하는 시험 (소유자용 안내)

무엇을 보는가
  Windows 11 26H2의 설정 앱은 가장자리를 끌면 앱이 새 크기를 그릴 수 있을 때까지 창의 틀이
  오지 않습니다. 보통의 창은 그렇지 않습니다(이전 시험에서 일부러 50 ms 늦게 그리는 창
  resize-control이 실패했습니다). 이번 키트의 resize-sync-control.exe는 설정 앱이 쓰는 같은
  장치(비공개 크기 동기화)를 써서 50 ms 늦게 그리는 창입니다. 이 장치가 이 기계에서 되면
  늦게 그려도 빈 곳, 검은 줄, 낡은 그림이 없어야 하고, 대신 창이 마우스를 조금 늦게 따라옵니다.

돌리는 법 (약 15분, 도중에 마우스를 만지지 마세요)
  1. 받은 압축을 풀고 run-probe-v2.cmd를 두 번 누릅니다. 화면 배율은 지금처럼 200%로 둡니다.
  2. "Done"이 나올 때까지 기다립니다. 같은 폴더에 probe-logs.zip이 생깁니다.
  3. probe-logs.zip을 보내 주세요.

표에서 볼 줄 (summary.md)
  control   late     : 장치 없이 50 ms 늦게 그리는 창. 이전처럼 FAIL이면 정상입니다.
  sync      late     : 장치를 쓰고 50 ms 늦게 그리는 창. PASS면 이 기계에서 장치가 된 것입니다.
  sync      prompt   : 장치를 쓰고 늦지 않게 그리는 창. 장치 자체의 지연이 이 값입니다.
  sync      swapchain: 다른 방식(합성 스왑체인). FAIL이 나오는 것이 예상입니다.
  sync      on12     : 렌더러와 같은 장치 구성(Direct3D 12 위의 11)으로 그린 창. PASS가 예상입니다.
  kn        sync     : hand-check(Compose 창)에 같은 장치를 켠 것. 키트의 hand-check.exe가 이 모드를
                       가진 빌드일 때만 나옵니다(README.txt 끝의 빌드 정보 참고).
  맨 오른쪽 칸에 "sync=on, tokens N of N"이 있으면 장치가 크기마다 신호를 줬다는 뜻이고,
  "sync=off"면 이 기계에서 장치가 빠져 있다는 뜻입니다(어느 부분인지는 로그에 있습니다).

눈으로 비교하기 (1분)
  1. resize-sync-control.exe를 두 번 눌러 엽니다(640x480 창). 가장자리를 천천히, 그리고
     빠르게 끌어 봅니다. 느리게 따라오지만 빈 곳이나 검은 줄이 없어야 하고, 설정 앱을 끄는
     것과 같은 느낌이어야 합니다.
  2. 같은 폴더에서 명령 프롬프트로 resize-sync-control.exe --delay-ms 0 을 열어 봅니다.
     늦게 그리지 않으므로 빠르게 따라옵니다.
  3. resize-control.exe(장치 없음)를 열어 같은 식으로 끌어 보면 빈 곳이나 검은 줄이 보입니다.
  4. hand-check.exe가 이 모드를 가진 빌드면, 명령 프롬프트에서
         set DXC_RESIZE_SYNC=1
         hand-check.exe
     로 열어 끌어 봅니다. 창 안에 "resize-sync on"이 아니라 아무 일도 없으면 장치가 빠진 것이니
     그 사실만 알려 주세요.

기록해 둘 것
  - 설정 앱과 sync late 창을 끌 때의 느낌이 같은가, 다른가 (한 줄이면 됩니다).
  - sync late가 FAIL이거나 sync=off면 summary.md의 그 줄과 probe-logs.zip.
