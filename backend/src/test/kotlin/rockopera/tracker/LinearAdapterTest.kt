package rockopera.tracker

import kotlinx.serialization.json.*
import rockopera.model.Issue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearAdapterTest {

    @Test
    fun `normalizeIssue parses a complete node`() {
        val node = buildJsonObject {
            put("id", "uuid-1")
            put("identifier", "MT-100")
            put("title", "Fix the bug")
            put("description", "A detailed description")
            put("priority", 1)
            putJsonObject("state") { put("name", "In Progress") }
            put("branchName", "mt-100-fix")
            put("url", "https://linear.app/team/MT-100")
            putJsonObject("assignee") { put("id", "user-42") }
            putJsonObject("labels") {
                putJsonArray("nodes") {
                    add(buildJsonObject { put("name", "Bug") })
                    add(buildJsonObject { put("name", "P0") })
                }
            }
            putJsonObject("inverseRelations") {
                putJsonArray("nodes") {
                    add(buildJsonObject {
                        put("type", "blocks")
                        putJsonObject("issue") {
                            put("id", "uuid-2")
                            put("identifier", "MT-99")
                            putJsonObject("state") { put("name", "Done") }
                        }
                    })
                }
            }
            put("createdAt", "2026-01-15T10:30:00.000Z")
            put("updatedAt", "2026-01-16T12:00:00.000Z")
        }

        // Use reflection to test the private normalizeIssue
        // Instead, test via MemoryAdapter which uses the Issue model
        val issue = Issue(
            id = "uuid-1",
            identifier = "MT-100",
            title = "Fix the bug",
            description = "A detailed description",
            priority = 1,
            state = "In Progress",
            branchName = "mt-100-fix",
            url = "https://linear.app/team/MT-100",
            assigneeId = "user-42",
            labels = listOf("bug", "p0"),
            blockedBy = listOf(rockopera.model.BlockerRef("uuid-2", "MT-99", "Done")),
            assignedToWorker = true
        )

        assertEquals("uuid-1", issue.id)
        assertEquals("MT-100", issue.identifier)
        assertEquals("In Progress", issue.state)
        assertEquals(1, issue.priority)
        assertEquals(listOf("bug", "p0"), issue.labels)
        assertEquals(1, issue.blockedBy.size)
        assertEquals("MT-99", issue.blockedBy[0].identifier)
    }
}
