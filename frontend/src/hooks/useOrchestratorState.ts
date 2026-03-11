import { useQuery } from '@tanstack/react-query'
import type { OrchestratorState } from '../types/api'

async function fetchState(): Promise<OrchestratorState> {
  const res = await fetch('/api/v1/state')
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export function useOrchestratorState() {
  return useQuery<OrchestratorState>({
    queryKey: ['orchestrator-state'],
    queryFn: fetchState,
    refetchInterval: 1000,
  })
}
