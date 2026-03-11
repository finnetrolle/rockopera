package rockopera.tracker

import rockopera.model.Issue

interface TrackerAdapter {
    suspend fun fetchCandidateIssues(): Result<List<Issue>>
    suspend fun fetchIssuesByStates(stateNames: List<String>): Result<List<Issue>>
    suspend fun fetchIssueStatesByIds(issueIds: List<String>): Result<List<Issue>>
}
