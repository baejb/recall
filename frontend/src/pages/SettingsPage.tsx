import { useState } from 'react'
import { useSettings } from '../hooks/useSettings'
import { useToast } from '../hooks/useToast'
import type {
  EmbeddingStatus,
  ModelSettings,
  ModelUpdate,
  SettingsResponse,
  SettingsUpdateRequest,
} from '../api/dto'

type Role = 'chat' | 'embedding'

/** 편집 가능한 로컬 폼(파생 상태 아님). apiKey는 사용자가 입력한 값만 담고 서버 값은 절대 담지 않는다. */
interface SectionForm {
  provider: string
  model: string
  apiKey: string
  baseUrl: string
}

type FormState = Record<Role, SectionForm>

function toForm(s: SettingsResponse): FormState {
  return {
    chat: {
      provider: s.chat.provider,
      model: s.chat.model,
      apiKey: '',
      baseUrl: s.chat.baseUrl ?? '',
    },
    embedding: {
      provider: s.embedding.provider,
      model: s.embedding.model,
      apiKey: '',
      baseUrl: s.embedding.baseUrl ?? '',
    },
  }
}

/** 로드된 값과 폼을 비교해 실제로 바뀐 것만 담는다. apiKey는 입력했을 때만 담는다(빈값=유지 — 비밀은 빈 입력으로 지우지 않는다).
 * baseUrl은 바뀌면 담는다 — 비우면 공백("")으로 보내 해제 신호(계약: 공백=provider 기본값으로 리셋). */
function buildSectionUpdate(form: SectionForm, loaded: ModelSettings): ModelUpdate {
  const upd: ModelUpdate = {}
  if (form.provider !== loaded.provider) upd.provider = form.provider
  if (form.model !== loaded.model) upd.model = form.model
  if (form.apiKey.trim() !== '') upd.apiKey = form.apiKey
  const loadedBase = loaded.baseUrl ?? ''
  if (form.baseUrl !== loadedBase) upd.baseUrl = form.baseUrl
  return upd
}

const STATUS_META: Record<EmbeddingStatus, { cls: string; label: string }> = {
  READY: { cls: 'ok', label: 'READY' },
  REINDEXING: { cls: 'warn', label: 'REINDEXING' },
  FAILED: { cls: 'bad', label: 'FAILED' },
}

function StatusBadge({ status }: { status: EmbeddingStatus }) {
  const m = STATUS_META[status]
  return <span className={`pill ${m.cls}`}>{m.label}</span>
}

interface ModelSectionProps {
  title: string
  hint: string
  providers: string[]
  modelsForProvider: string[]
  form: SectionForm
  apiKeyConfigured: boolean
  onProviderChange: (provider: string) => void
  onModelChange: (model: string) => void
  onApiKeyChange: (key: string) => void
  onBaseUrlChange: (url: string) => void
  badge?: React.ReactNode
  warning?: string
}

/** 채팅/임베딩 공통 편집 카드(표시 전용). */
function ModelSection(props: ModelSectionProps) {
  const {
    title,
    hint,
    providers,
    modelsForProvider,
    form,
    apiKeyConfigured,
    onProviderChange,
    onModelChange,
    onApiKeyChange,
    onBaseUrlChange,
    badge,
    warning,
  } = props
  return (
    <div className="card pad" style={{ marginBottom: 16 }}>
      <div className="between" style={{ marginBottom: 12 }}>
        <div style={{ fontWeight: 700, fontSize: 16 }}>{title}</div>
        {badge}
      </div>
      <p style={{ fontSize: 13, color: 'var(--text-muted)', margin: '0 0 16px' }}>{hint}</p>

      <label className="field-label">Provider</label>
      <select value={form.provider} onChange={(e) => onProviderChange(e.target.value)}>
        {providers.map((p) => (
          <option key={p} value={p}>
            {p}
          </option>
        ))}
      </select>

      <label className="field-label" style={{ marginTop: 14 }}>
        모델
      </label>
      <select value={form.model} onChange={(e) => onModelChange(e.target.value)}>
        {/* 현재 값이 목록에 없으면(미설정 등) 셀렉트 표시와 상태가 어긋나지 않게 항목을 보강한다. */}
        {!modelsForProvider.includes(form.model) && (
          <option value={form.model}>{form.model === '' ? '(모델 선택)' : form.model}</option>
        )}
        {modelsForProvider.map((m) => (
          <option key={m} value={m}>
            {m}
          </option>
        ))}
      </select>

      <label className="field-label" style={{ marginTop: 14 }}>
        API 키
      </label>
      <input
        type="password"
        value={form.apiKey}
        autoComplete="off"
        placeholder={apiKeyConfigured ? '설정됨 (변경하려면 입력)' : '키 입력'}
        onChange={(e) => onApiKeyChange(e.target.value)}
      />

      <label className="field-label" style={{ marginTop: 14 }}>
        Base URL <span style={{ fontWeight: 400 }}>(선택 · https만)</span>
      </label>
      <input
        type="url"
        value={form.baseUrl}
        placeholder="예: https://api.openai.com/v1 (보통 비워두면 됨)"
        onChange={(e) => onBaseUrlChange(e.target.value)}
      />
      <p style={{ fontSize: 12, color: 'var(--text-muted)', margin: '6px 0 0' }}>
        엔드포인트 경로 전까지 · 비우면 provider 기본값
      </p>

      {warning && (
        <p style={{ fontSize: 12.5, color: 'var(--warn)', margin: '10px 0 0' }}>{warning}</p>
      )}
    </div>
  )
}

export function SettingsPage() {
  const { settings, catalog, loading, error, reload, save } = useSettings()
  const toast = useToast()
  const [form, setForm] = useState<FormState | null>(null)
  const [formReady, setFormReady] = useState(false)
  const [saving, setSaving] = useState(false)
  const [justSaved, setJustSaved] = useState(false)

  // settings 최초 로드 시 한 번만 폼 초기화. 렌더 중 조건부 setState(React 권장 "이전 렌더 정보로 상태 조정"
  // 패턴, 이펙트 아님) — 이후 재색인 폴링으로 status만 갱신될 때는 폼을 덮어쓰지 않아 미저장 편집이 보존된다.
  if (settings && !formReady) {
    setForm(toForm(settings))
    setFormReady(true)
  }

  if (error && !settings) {
    return (
      <section className="screen">
        <div className="eyebrow">설정</div>
        <h1 className="h1">모델 설정</h1>
        <div className="card empty">
          <div className="big">⚠️</div>
          <p style={{ fontWeight: 600, margin: '10px 0 2px' }}>설정을 불러오지 못했어요</p>
          <p style={{ fontSize: 13, color: 'var(--text-muted)', margin: '0 0 14px' }}>{error}</p>
          <button className="btn" onClick={reload}>
            다시 시도
          </button>
        </div>
      </section>
    )
  }

  if (loading || !settings || !catalog || !form) {
    return (
      <section className="screen">
        <div className="eyebrow">설정</div>
        <h1 className="h1">모델 설정</h1>
        <div className="card pad">불러오는 중…</div>
      </section>
    )
  }

  const chatProviders = Object.keys(catalog.chatModels)
  const embeddingProviders = Object.keys(catalog.embeddingModels)
  const chatModels = catalog.chatModels[form.chat.provider] ?? []
  const embeddingModels = catalog.embeddingModels[form.embedding.provider] ?? []

  const updateSection = (role: Role, patch: Partial<SectionForm>) => {
    setJustSaved(false) // 저장 후 편집 시작 → "저장됨" 표시를 지운다.
    setForm((prev) => (prev ? { ...prev, [role]: { ...prev[role], ...patch } } : prev))
  }

  // provider 변경 시 그 provider의 첫 추천 모델을 자동 선택(백엔드 auto-default 미러 → 조합 일관성 유지).
  const onProviderChange = (role: Role, provider: string) => {
    const models = (role === 'chat' ? catalog.chatModels : catalog.embeddingModels)[provider] ?? []
    updateSection(role, { provider, model: models[0] ?? '' })
  }

  // 클라 힌트(백엔드가 실제 게이트): chat provider를 바꿨는데 키가 비면 400이 확실하므로 미리 막고 안내.
  const chatProviderChanged = form.chat.provider !== settings.chat.provider
  const blockChatSave = chatProviderChanged && form.chat.apiKey.trim() === ''

  const onSave = async () => {
    const body: SettingsUpdateRequest = {}
    const chat = buildSectionUpdate(form.chat, settings.chat)
    const embedding = buildSectionUpdate(form.embedding, settings.embedding)
    if (Object.keys(chat).length > 0) body.chat = chat
    if (Object.keys(embedding).length > 0) body.embedding = embedding
    if (!body.chat && !body.embedding) {
      toast('변경할 내용이 없어요')
      return
    }
    setSaving(true)
    try {
      const updated = await save(body)
      setForm(toForm(updated)) // 저장 성공 → 폼 재설정(입력했던 키 필드 비움)
      setJustSaved(true) // 다음 편집 전까지 유지되는 지속 표시(토스트는 잠깐만 보임)
      toast('저장됨')
    } catch (e) {
      // ProblemDetail 의 detail(사람이 읽을 메시지)이 그대로 넘어온다.
      toast(`⚠️ ${e instanceof Error ? e.message : '저장 실패'}`)
    } finally {
      setSaving(false)
    }
  }

  const embStatus = settings.embedding.status

  return (
    <section className="screen">
      <div className="eyebrow">설정</div>
      <h1 className="h1">모델 설정</h1>
      <p className="lede">
        답변 생성(채팅)과 검색용 임베딩에 쓸 provider·모델·키를 설정해요. 키는 서버에만 저장되고
        화면에 다시 보이지 않아요.
      </p>

      {embStatus === 'REINDEXING' && (
        <div className="note" style={{ marginBottom: 14 }}>
          <b>재색인 중</b>
          <span>임베딩을 다시 계산하는 동안 검색이 키워드 위주로 동작해요. 잠시만요…</span>
        </div>
      )}
      {embStatus === 'FAILED' && (
        <div
          className="note"
          style={{
            marginBottom: 14,
            borderLeftColor: 'var(--bad)',
            background: 'var(--bad-soft)',
            color: 'var(--bad)',
          }}
        >
          <b>재색인 실패</b>
          <span>임베딩 재색인이 실패했어요. 키·모델을 확인하고 다시 저장해 주세요.</span>
        </div>
      )}

      <ModelSection
        title="채팅 (생성)"
        hint="질문에 답을 생성하는 모델. provider를 바꾸면 새 API 키가 필요해요."
        providers={chatProviders}
        modelsForProvider={chatModels}
        form={form.chat}
        apiKeyConfigured={settings.chat.apiKeyConfigured}
        onProviderChange={(p) => onProviderChange('chat', p)}
        onModelChange={(m) => updateSection('chat', { model: m })}
        onApiKeyChange={(k) => updateSection('chat', { apiKey: k })}
        onBaseUrlChange={(u) => updateSection('chat', { baseUrl: u })}
        warning={blockChatSave ? 'provider 변경 시 새 키가 필요해요' : undefined}
      />

      <ModelSection
        title="임베딩"
        hint="검색용 벡터를 만드는 모델. provider·모델을 바꾸려면 유효한 키가 필요해요(기존 벡터 보호)."
        providers={embeddingProviders}
        modelsForProvider={embeddingModels}
        form={form.embedding}
        apiKeyConfigured={settings.embedding.apiKeyConfigured}
        onProviderChange={(p) => onProviderChange('embedding', p)}
        onModelChange={(m) => updateSection('embedding', { model: m })}
        onApiKeyChange={(k) => updateSection('embedding', { apiKey: k })}
        onBaseUrlChange={(u) => updateSection('embedding', { baseUrl: u })}
        badge={<StatusBadge status={embStatus} />}
      />

      <div className="row" style={{ alignItems: 'center' }}>
        <button
          className="btn primary"
          onClick={() => void onSave()}
          disabled={saving || blockChatSave}
        >
          {saving ? '저장 중…' : '저장'}
        </button>
        {justSaved && (
          <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--ok)' }}>✓ 저장됨</span>
        )}
      </div>
      <div className="note">
        <b>설계</b>
        <span>
          실제 키는 화면에 표시되지 않아요(설정 여부만). 임베딩 provider·모델 변경은 유효한 키
          검증을 거쳐야 기존 벡터를 보호해요.
        </span>
      </div>
    </section>
  )
}
