package rockopera.agent

import liqp.TemplateParser
import rockopera.model.Issue

object PromptBuilder {

    private val DEFAULT_TEMPLATE = """
        You are working on a Linear issue.

        Identifier: {{ issue.identifier }}
        Title: {{ issue.title }}

        Body:
        {% if issue.description %}
        {{ issue.description }}
        {% else %}
        No description provided.
        {% endif %}
    """.trimIndent()

    private val DEFAULT_REVIEW_TEMPLATE = """
        You are a code reviewer. Review the following pull request.

        Issue identifier: {{ issue.identifier }}
        Issue title: {{ issue.title }}

        Issue description:
        {% if issue.description %}
        {{ issue.description }}
        {% else %}
        No description provided.
        {% endif %}

        Pull request: #{{ pr.number }} — {{ pr.title }}

        Changed files (diff):
        ```
        {{ pr.diff }}
        ```

        Instructions:
        - Review the code changes for correctness, style, and potential issues.
        - You MUST end your response with a JSON block in the following exact format:

        ```json
        ROCKOPERA_REVIEW_START
        {
          "verdict": "APPROVED" or "CHANGES_REQUESTED",
          "summary": "Overall review summary text",
          "comments": [
            {
              "path": "relative/path/to/file.py",
              "line": 42,
              "body": "Your inline comment about this specific line"
            }
          ]
        }
        ROCKOPERA_REVIEW_END
        ```

        Rules for the review JSON:
        - "verdict" is required: use "APPROVED" if the code is acceptable, "CHANGES_REQUESTED" if it needs fixes.
        - "summary" is required: a brief overall assessment of the PR.
        - "comments" is an array of inline comments. Each must have "path" (file path as shown in the diff), "line" (line number in the NEW version of the file), and "body" (your comment). Can be empty array if no inline comments.
        - The "line" must be a line number from the new version of the file (lines starting with "+" or unchanged lines in the diff).
        - Be specific and actionable in your comments.
        - The JSON block must be the very last thing in your response, after any discussion.
    """.trimIndent()

    fun render(
        templateText: String,
        issue: Issue,
        attempt: Int?,
        prContext: PrContext? = null
    ): String {
        val effectiveTemplate = when {
            templateText.isNotBlank() && (prContext == null || templateText.contains("{{ pr.")) -> templateText
            prContext != null -> DEFAULT_REVIEW_TEMPLATE
            templateText.isNotBlank() -> templateText
            else -> DEFAULT_TEMPLATE
        }

        val issueMap = mapOf(
            "id" to issue.id,
            "identifier" to issue.identifier,
            "title" to issue.title,
            "description" to (issue.description ?: ""),
            "priority" to issue.priority,
            "state" to issue.state,
            "branchName" to (issue.branchName ?: ""),
            "url" to (issue.url ?: ""),
            "assigneeId" to (issue.assigneeId ?: ""),
            "labels" to issue.labels,
            "blockedBy" to issue.blockedBy.map { blocker ->
                mapOf(
                    "id" to (blocker.id ?: ""),
                    "identifier" to (blocker.identifier ?: ""),
                    "state" to (blocker.state ?: "")
                )
            },
            "createdAt" to issue.createdAt?.toString(),
            "updatedAt" to issue.updatedAt?.toString()
        )

        val parser = TemplateParser.Builder()
            .withStrictVariables(true)
            .build()

        val template = parser.parse(effectiveTemplate)

        // Liqp strict mode treats null as "variable not exist".
        // Use `false` as falsy sentinel so {% if attempt %} works correctly.
        val context = mutableMapOf<String, Any>(
            "issue" to issueMap,
            "attempt" to (attempt ?: false)
        )

        if (prContext != null) {
            context["pr"] = mapOf(
                "number" to prContext.number,
                "title" to prContext.title,
                "diff" to prContext.diff
            )
        }

        return template.render(context)
    }

    fun continuationPrompt(turnNumber: Int, maxTurns: Int): String = """
        Continuation guidance:

        - The previous agent turn completed normally, but the issue is still in an active state.
        - This is continuation turn #$turnNumber of $maxTurns for the current agent run.
        - Resume from the current workspace state instead of restarting from scratch.
        - The original task instructions and prior turn context are already present, so do not restate them before acting.
        - Focus on the remaining ticket work and do not end the turn while the issue stays active unless you are truly blocked.
    """.trimIndent()
}

data class PrContext(
    val number: Long,
    val title: String,
    val diff: String
)
