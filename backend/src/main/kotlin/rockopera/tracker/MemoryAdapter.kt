package rockopera.tracker

import rockopera.model.Issue
import rockopera.model.IssueComment

class MemoryAdapter(
    private val issues: MutableList<Issue> = mutableListOf(),
    private val commentsByIssueId: MutableMap<String, MutableList<IssueComment>> = mutableMapOf()
) : TrackerAdapter {

    fun addIssue(issue: Issue) {
        issues.add(issue)
    }

    fun addComment(issueId: String, comment: IssueComment) {
        commentsByIssueId.getOrPut(issueId) { mutableListOf() }.add(comment)
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

    override suspend fun fetchIssueComments(issueId: String): Result<List<IssueComment>> =
        Result.success(commentsByIssueId[issueId]?.toList() ?: emptyList())
}
