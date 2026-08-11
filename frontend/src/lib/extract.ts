import type { ReviewCard } from '../types'

// 목업의 아주 단순한 추출 흉내. docker/권한류면 트러블슈팅 카드, 아니면 첫 줄 제목의 일반 카드.
// 실제 S2 추출은 백엔드 LLM 담당 — 여기선 흐름 검증용 결정론 mock.

export function mockExtract(text: string): ReviewCard {
  const low = text.toLowerCase()
  if (low.indexOf('docker') >= 0 || low.indexOf('chown') >= 0 || text.indexOf('권한') >= 0) {
    return {
      type: 'ts',
      title: 'Docker 볼륨 마운트 권한 거부 (EACCES)',
      ts: {
        problem: 'compose up 시 컨테이너가 볼륨에 쓰기 → Permission denied',
        tried: 'chmod 777(폐기), 재빌드(실패)',
        solution: 'Dockerfile USER 지정 + 마운트 경로 chown으로 UID 정렬',
        status: '해결',
      },
      kn: { content: '' },
    }
  }
  const first = (text.trim().split('\n')[0] || '붙여넣은 세션')
    .replace(/^\[.*?\]\s*/, '')
    .slice(0, 40)
  return {
    type: 'ts',
    title: first,
    ts: { problem: first, tried: '', solution: '', status: '미해결' },
    kn: { content: text.slice(0, 120) },
  }
}
