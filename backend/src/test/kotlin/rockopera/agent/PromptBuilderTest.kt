package rockopera.agent

import rockopera.model.Issue
import kotlin.test.Test
import kotlin.test.assertContains
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
}
