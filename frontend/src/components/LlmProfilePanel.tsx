import { useEffect, useState, type FormEvent } from 'react'
import type { LlmProfileSnapshot } from '../types/api'

interface LlmProfilePanelProps {
  data: LlmProfileSnapshot | undefined
  isLoading: boolean
  isError: boolean
  isSwitching: boolean
  errorMessage?: string
  onSwitch: (profileId: string) => Promise<unknown>
}

export function LlmProfilePanel({
  data,
  isLoading,
  isError,
  isSwitching,
  errorMessage,
  onSwitch,
}: LlmProfilePanelProps) {
  const [selectedProfileId, setSelectedProfileId] = useState('')

  useEffect(() => {
    setSelectedProfileId(
      data?.active_profile_id ?? data?.default_profile_id ?? data?.profiles[0]?.id ?? '',
    )
  }, [data?.active_profile_id, data?.default_profile_id, data?.profiles])

  const profiles = data?.profiles ?? []
  const activeProfile = profiles.find((profile) => profile.active)
  const effectiveActiveId = data?.active_profile_id ?? data?.default_profile_id ?? null
  const canSubmit =
    !!selectedProfileId &&
    selectedProfileId !== (effectiveActiveId ?? '') &&
    !isSwitching

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canSubmit) return
    await onSwitch(selectedProfileId)
  }

  if (isLoading) {
    return (
      <section className="llm-panel">
        <div className="llm-panel-head">
          <div>
            <h2>LLM Profile</h2>
            <p>Loading profiles...</p>
          </div>
        </div>
      </section>
    )
  }

  if (isError) {
    return (
      <section className="llm-panel">
        <div className="llm-panel-head">
          <div>
            <h2>LLM Profile</h2>
            <p className="llm-panel-error">Failed to load LLM profiles.</p>
          </div>
        </div>
      </section>
    )
  }

  if (profiles.length === 0) {
    return (
      <section className="llm-panel">
        <div className="llm-panel-head">
          <div>
            <h2>LLM Profile</h2>
            <p>No profiles configured in `WORKFLOW.md`.</p>
          </div>
        </div>
      </section>
    )
  }

  return (
    <section className="llm-panel">
      <div className="llm-panel-head">
        <div>
          <h2>LLM Profile</h2>
          <p>Switch applies to new agent runs. Running sessions continue with their current profile.</p>
        </div>
        <span className="llm-panel-badge">
          Active: {activeProfile?.label ?? 'Inherited environment'}
        </span>
      </div>

      <form className="llm-panel-form" onSubmit={handleSubmit}>
        <label className="llm-panel-field">
          <span>Profile</span>
          <select
            value={selectedProfileId}
            onChange={(event) => setSelectedProfileId(event.target.value)}
            disabled={isSwitching}
          >
            {profiles.map((profile) => (
              <option key={profile.id} value={profile.id}>
                {profile.label}
              </option>
            ))}
          </select>
        </label>

        <button type="submit" disabled={!canSubmit}>
          {isSwitching ? 'Switching...' : 'Apply'}
        </button>
      </form>

      {errorMessage && <p className="llm-panel-error">{errorMessage}</p>}
    </section>
  )
}
