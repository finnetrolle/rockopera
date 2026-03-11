package rockopera.workspace

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceManagerTest {

    @Test
    fun `sanitizeKey replaces non-safe characters with underscore`() {
        assertEquals("ABC-123", WorkspaceManager.sanitizeKey("ABC-123"))
        assertEquals("ABC_123_test", WorkspaceManager.sanitizeKey("ABC/123/test"))
        assertEquals("issue_with_spaces", WorkspaceManager.sanitizeKey("issue with spaces"))
        assertEquals("MT_42", WorkspaceManager.sanitizeKey("MT#42"))
    }
}
