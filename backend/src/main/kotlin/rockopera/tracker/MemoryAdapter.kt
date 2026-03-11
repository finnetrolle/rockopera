package rockopera.tracker

import rockopera.model.Issue

class MemoryAdapter(
    private val issues: MutableList<Issue> = mutableListOf()
) : TrackerAdapter {

    fun addIssue(issue: Issue) {
        issues.add(issue)
    }

    fun updateIssueState(issueId: String, state: String) {
        val idx = issues.indexOfFirst { it.id == issueId }
        if (idx >= 0) {
            issues[idx] = issues[idx].copy(state = state)
        }
    }

    override suspend fun fetchCandidateIssues(): Result<List<Issue>> =
        Result.success(issues.toList())

    override suspend fun fetchIssuesByStates(stateNames: List<String>): Result<List<Issue>> {
        val normalized = stateNames.map { it.trim().lowercase() }
        return Result.success(issues.filter { it.state.trim().lowercase() in normalized })
    }

    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Result<List<Issue>> =
        Result.success(issues.filter { it.id in issueIds })
}
