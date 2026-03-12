package rockopera.agent

import rockopera.model.Issue
import rockopera.model.IssueComment
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptBuilderTest {

    @Test
    fun `render with template variables`() {
        val template = "Fix issue {{ issue.identifier }}: {{ issue.title }}"
        val issue = Issue(
            id = "1", identifier = "MT-42", title = "Fix login bug", state = "Todo"
        )

        val result = PromptBuilder.render(template, issue, null)

        assertContains(result, "MT-42")
        assertContains(result, "Fix login bug")
    }

    @Test
    fun `render uses default template when blank`() {
        val issue = Issue(
            id = "1", identifier = "ABC-1", title = "Test Issue",
            description = "Some description", state = "Todo"
        )

        val result = PromptBuilder.render("", issue, null)

        assertContains(result, "ABC-1")
        assertContains(result, "Test Issue")
        assertContains(result, "Some description")
    }

    @Test
    fun `continuationPrompt contains turn info`() {
        val prompt = PromptBuilder.continuationPrompt(3, 20)

        assertContains(prompt, "continuation turn #3")
        assertContains(prompt, "of 20")
        assertContains(prompt, "Resume from the current workspace")
    }

    @Test
    fun `render with attempt value`() {
        val template = "Attempt: {% if attempt %}{{ attempt }}{% else %}first{% endif %}"
        val issue = Issue(id = "1", identifier = "X-1", title = "T", state = "Todo")

        val first = PromptBuilder.render(template, issue, null)
        assertContains(first, "first")

        val retry = PromptBuilder.render(template, issue, 3)
        assertContains(retry, "3")
    }

    @Test
    fun `render includes review comments when provided`() {
        val issue = Issue(
            id = "1", identifier = "ABC-1", title = "Test Issue",
            description = "Some description", state = "Todo"
        )
        val comments = listOf(
            IssueComment(
                author = "reviewer-bot",
                body = "Please fix the null check in UserService.kt",
                createdAt = Instant.parse("2026-01-15T10:30:00Z")
            ),
            IssueComment(
                author = "lead-dev",
                body = "Also add unit tests for the edge case",
                createdAt = null
            )
        )

        val result = PromptBuilder.render("", issue, null, reviewComments = comments)

        assertContains(result, "Previous review feedback")
        assertContains(result, "reviewer-bot")
        assertContains(result, "Please fix the null check in UserService.kt")
        assertContains(result, "lead-dev")
        assertContains(result, "Also add unit tests for the edge case")
        assertContains(result, "Address all the review feedback")
    }

    @Test
    fun `render omits review section when no comments`() {
        val issue = Issue(
            id = "1", identifier = "ABC-1", title = "Test Issue",
            description = "Some description", state = "Todo"
        )

        val result = PromptBuilder.render("", issue, null, reviewComments = emptyList())

        assertFalse(result.contains("Previous review feedback"))
        assertFalse(result.contains("Address all the review feedback"))
    }

    @Test
    fun `render with custom template and review_comments`() {
        val template = """
            Task: {{ issue.title }}
            {% if review_comments.size > 0 %}
            Review notes:
            {% for c in review_comments %}
            - {{ c.author }}: {{ c.body }}
            {% endfor %}
            {% endif %}
        """.trimIndent()

        val issue = Issue(id = "1", identifier = "X-1", title = "Fix bug", state = "Todo")
        val comments = listOf(
            IssueComment(author = "alice", body = "Missing error handling", createdAt = null)
        )

        val result = PromptBuilder.render(template, issue, null, reviewComments = comments)

        assertContains(result, "Fix bug")
        assertContains(result, "Review notes")
        assertContains(result, "alice: Missing error handling")
    }
}
