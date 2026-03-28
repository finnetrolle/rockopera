import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { LlmProfileSnapshot } from '../types/api'

async function fetchLlmProfiles(): Promise<LlmProfileSnapshot> {
  const res = await fetch('/api/v1/llm/profiles')
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

async function switchLlmProfile(profileId: string): Promise<LlmProfileSnapshot> {
  const res = await fetch('/api/v1/llm/active', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ profile_id: profileId }),
  })

  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`)
  }

  return res.json()
}

export function useLlmProfiles() {
  const queryClient = useQueryClient()

  const query = useQuery<LlmProfileSnapshot>({
    queryKey: ['llm-profiles'],
    queryFn: fetchLlmProfiles,
  })

  const mutation = useMutation({
    mutationFn: switchLlmProfile,
    onSuccess: (data) => {
      queryClient.setQueryData(['llm-profiles'], data)
    },
  })

  return {
    ...query,
    switchProfile: mutation.mutateAsync,
    isSwitching: mutation.isPending,
    switchError: mutation.error,
  }
}
