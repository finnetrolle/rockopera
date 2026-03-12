package rockopera.tracker

import rockopera.model.Issue
import rockopera.model.IssueComment

interface TrackerAdapter {
    suspend fun fetchCandidateIssues(): Result<List<Issue>>
    suspend fun fetchIssuesByStates(stateNames: List<String>): Result<List<Issue>>
    suspend fun fetchIssueStatesByIds(issueIds: List<String>): Result<List<Issue>>

    /**
     * Fetch comments for a specific issue. Used to provide review feedback context
     * when an issue is picked up for rework after code review.
     */
    suspend fun fetchIssueComments(issueId: String): Result<List<IssueComment>> =
        Result.success(emptyList())
}
