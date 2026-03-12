package rockopera.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class Issue(
    val id: String,
    val identifier: String,
    val title: String,
    val description: String? = null,
    val priority: Int? = null,
    val state: String,
    val branchName: String? = null,
    val url: String? = null,
    val assigneeId: String? = null,
    val labels: List<String> = emptyList(),
    val blockedBy: List<BlockerRef> = emptyList(),
    val assignedToWorker: Boolean = true,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant? = null,
    val comments: List<IssueComment> = emptyList()
)

@Serializable
data class IssueComment(
    val author: String,
    val body: String,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null
)

@Serializable
data class BlockerRef(
    val id: String? = null,
    val identifier: String? = null,
    val state: String? = null
)
