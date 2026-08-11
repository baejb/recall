import type { Capture, Memory, Review } from '../types'

// 목업 seed — 신입 개발자 이혜린이 몇 달에 걸쳐 반복해 부딪힌 문제들.
// mock 단계 전용. 실제 백엔드 연동 시 이 파일은 제거되고 /api 응답으로 대체된다.

export const SEED_CAPTURES: Capture[] = [
  {
    id: 'c1',
    masked:
      '[나] docker compose up 하면 postgres가 볼륨에 못 써. Permission denied (EACCES)\n    참고로 .env 에 DB_PASSWORD=●●●●●●● 있음\n[클로드] 호스트 볼륨 소유자 UID와 컨테이너 유저 UID가 안 맞아서예요.\n    Dockerfile에서 USER를 지정하고 마운트 경로를 chown 하세요.\n[나] 오 chown 하니까 됐다!',
    spans: [{ key: 'DB_PASSWORD' }],
    created: '2026-04-01',
  },
  {
    id: 'c2',
    masked:
      '[나] 하이브리드 검색에서 RRF가 뭐야?\n[클로드] 여러 검색 결과의 순위를 1/(k+순위)로 환산해 더하는 방법이에요.',
    spans: [],
    created: '2026-06-18',
  },
  {
    id: 'c3',
    masked:
      '[나] prod nginx가 502 뱉음. upstream 쪽인듯\n[클로드] proxy_read_timeout 을 늘려보세요.\n[나] 60s로 하니 해결!',
    spans: [],
    created: '2026-07-20',
  },
  {
    id: 'c4',
    masked:
      '[나] git push 했더니 ! [rejected] main -> main (non-fast-forward) 남\n[클로드] 원격에 로컬에 없는 커밋이 있어서예요. git pull --rebase 후 다시 push 하세요.\n[나] rebase 하고 푸시하니 됨',
    spans: [],
    created: '2026-04-15',
  },
  {
    id: 'c5',
    masked:
      '[나] 프론트에서 API 부르면 CORS preflight(OPTIONS)가 막혀. Access-Control-Allow-Origin 없다고 뜸\n[클로드] 서버 CORS 미들웨어로 허용 origin과 OPTIONS 메서드를 열어줘야 해요.\n[나] origin은 열었는데 credentials 붙은 요청은 아직 막힘…',
    spans: [],
    created: '2026-05-02',
  },
  {
    id: 'c6',
    masked:
      '[나] node에서 process.env.DB_URL 이 자꾸 undefined임\n    .env에 DB_URL=●●●●●●● 넣었는데\n[클로드] dotenv.config()보다 먼저 env를 읽는 모듈이 있어서예요. import 순서를 확인하세요.\n[나] config()를 맨 위로 올리니 됨',
    spans: [{ key: 'DB_URL' }],
    created: '2026-05-20',
  },
  {
    id: 'c7',
    masked:
      '[나] npm install 하면 ERESOLVE unable to resolve dependency tree 뜸\n[클로드] peer dependency 충돌이에요. 원인 패키지 버전을 맞추는 게 정석이고, 급하면 --legacy-peer-deps.',
    spans: [],
    created: '2026-06-30',
  },
  {
    id: 'c8',
    masked:
      '[나] 파이썬 스크립트 돌리면 ModuleNotFoundError: No module named requests\n[클로드] 설치한 venv와 실행 인터프리터가 달라서예요. venv 활성화하고 실행하세요.\n[나] source venv/bin/activate 하고 하니 됨',
    spans: [],
    created: '2026-07-10',
  },
]

const emptyKn = { content: '' }

export const SEED_MEMORIES: Memory[] = [
  {
    id: 'm1',
    captureId: 'c1',
    type: 'ts',
    title: 'Docker 볼륨 마운트 권한 거부 (EACCES)',
    created: '2026-04-01',
    status: 'active',
    firstSeen: '2026-04-01',
    lastSeen: '2026-07-28',
    hits: 3,
    keywords: 'docker 도커 권한 볼륨 eacces permission denied 컨테이너 마운트 postgres compose',
    ts: {
      problem: 'compose up 시 Postgres 컨테이너가 볼륨에 쓰기 → Permission denied (EACCES)',
      tried: 'chmod 777(폐기·보안), 컨테이너 재빌드(실패), UID 확인',
      solution: 'Dockerfile에서 USER 지정 + 마운트 경로를 chown 해 호스트/컨테이너 UID 정렬',
      status: '해결',
    },
    kn: emptyKn,
  },
  {
    id: 'm2',
    captureId: 'c2',
    type: 'kn',
    title: 'RRF — 검색 순위 합치기',
    created: '2026-06-18',
    status: 'active',
    firstSeen: '2026-06-18',
    lastSeen: '2026-06-18',
    hits: 1,
    keywords: 'rrf 검색 순위 하이브리드 fusion reciprocal rank',
    ts: { problem: '', tried: '', solution: '', status: '미해결' },
    kn: {
      content:
        '여러 검색 결과의 순위를 1/(k+순위)로 환산해 더함. 점수 체계가 다른 키워드·벡터 검색을 공정하게 결합할 때 사용.',
    },
  },
  {
    id: 'm3',
    captureId: 'c4',
    type: 'ts',
    title: 'git push 거부 — non-fast-forward',
    created: '2026-04-15',
    status: 'active',
    firstSeen: '2026-04-15',
    lastSeen: '2026-06-05',
    hits: 2,
    keywords: 'git push rejected non-fast-forward 원격 pull rebase 커밋 충돌',
    ts: {
      problem: 'git push 시 ! [rejected] (non-fast-forward) — 원격에 로컬에 없는 커밋 존재',
      tried: 'force push 하려다 위험해서 중단',
      solution: 'git pull --rebase 로 원격 커밋을 먼저 얹은 뒤 push',
      status: '해결',
    },
    kn: emptyKn,
  },
  {
    id: 'm4',
    captureId: 'c5',
    type: 'ts',
    title: 'CORS preflight(OPTIONS) 차단',
    created: '2026-05-02',
    status: 'active',
    firstSeen: '2026-05-02',
    lastSeen: '2026-07-25',
    hits: 2,
    keywords: 'cors preflight options origin credentials access-control 프론트 api 차단',
    ts: {
      problem:
        '프론트에서 API 호출 시 preflight(OPTIONS)가 막힘 — Access-Control-Allow-Origin 없음',
      tried: '서버 CORS 미들웨어로 허용 origin + OPTIONS 열기 → 일반 요청은 통과',
      solution:
        'credentials 포함 요청은 아직 막힘 — Allow-Credentials + 구체 origin 지정 필요 (미완)',
      status: '부분',
    },
    kn: emptyKn,
  },
  {
    id: 'm5',
    captureId: 'c6',
    type: 'ts',
    title: '.env 값이 undefined (dotenv 로드 순서)',
    created: '2026-05-20',
    status: 'active',
    firstSeen: '2026-05-20',
    lastSeen: '2026-05-20',
    hits: 1,
    keywords: 'env dotenv undefined process 환경변수 config import 순서 node',
    ts: {
      problem: 'process.env 값이 undefined — .env에 넣었는데도 안 읽힘',
      tried: '.env 위치·오타 확인',
      solution: 'dotenv.config()를 다른 모듈 import보다 먼저(최상단) 호출',
      status: '해결',
    },
    kn: emptyKn,
  },
  {
    id: 'm6',
    captureId: 'c7',
    type: 'ts',
    title: 'npm ERESOLVE — peer dependency 충돌',
    created: '2026-06-30',
    status: 'active',
    firstSeen: '2026-06-30',
    lastSeen: '2026-06-30',
    hits: 1,
    keywords: 'npm eresolve peer dependency 충돌 install legacy-peer-deps 버전',
    ts: {
      problem: 'npm install 시 ERESOLVE unable to resolve dependency tree',
      tried: '캐시 삭제·재설치(실패)',
      solution: '원인 패키지 버전 정렬이 정석. 급할 땐 --legacy-peer-deps (근본해결 아님)',
      status: '부분',
    },
    kn: emptyKn,
  },
  {
    id: 'm7',
    captureId: 'c8',
    type: 'ts',
    title: 'ModuleNotFoundError — venv 인터프리터 불일치',
    created: '2026-07-10',
    status: 'active',
    firstSeen: '2026-07-10',
    lastSeen: '2026-07-27',
    hits: 2,
    keywords: 'python 파이썬 modulenotfounderror venv 가상환경 인터프리터 pip requests 활성화',
    ts: {
      problem: '설치한 패키지인데 실행 시 ModuleNotFoundError',
      tried: 'pip install 재실행(같은 에러)',
      solution: '설치 venv와 실행 인터프리터 불일치 → venv 활성화 후 실행',
      status: '해결',
    },
    kn: emptyKn,
  },
]

export const SEED_REVIEWS: Review[] = [
  {
    id: 'r1',
    captureId: 'c3',
    cards: [
      {
        type: 'ts',
        title: 'nginx 502 (upstream 지연)',
        ts: {
          problem: 'prod nginx가 502 반환, upstream 응답 지연 의심',
          tried: '',
          solution: 'proxy_read_timeout 60s로 상향 → 해결',
          status: '해결',
        },
        kn: { content: '' },
      },
    ],
  },
]
