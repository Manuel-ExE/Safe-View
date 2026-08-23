package com.safeview.app

/** Categories shared by Screen AI, browser media filtering, and future MediaStore scans. */
enum class ContentCategory {
    SAFE,
    REVEALING,
    EXPLICIT,
    UNCERTAIN
}

enum class ProtectionMode {
    STRICT,
    BALANCED,
    WARNING
}

data class ClassificationResult(
    val category: ContentCategory,
    val confidence: Float
)

enum class PolicyAction {
    ALLOW,
    WARN,
    BLOCK
}

/** Central policy decision point. It never stores or receives the source media. */
object SafeViewPolicyEngine {
    fun decide(result: ClassificationResult, mode: ProtectionMode): PolicyAction {
        val confidence = result.confidence.coerceIn(0f, 1f)
        return when (mode) {
            ProtectionMode.STRICT -> when (result.category) {
                ContentCategory.SAFE -> PolicyAction.ALLOW
                ContentCategory.REVEALING, ContentCategory.EXPLICIT, ContentCategory.UNCERTAIN -> PolicyAction.BLOCK
            }
            ProtectionMode.BALANCED -> when (result.category) {
                ContentCategory.SAFE, ContentCategory.UNCERTAIN -> PolicyAction.ALLOW
                ContentCategory.REVEALING, ContentCategory.EXPLICIT -> PolicyAction.BLOCK
            }
            ProtectionMode.WARNING -> when (result.category) {
                ContentCategory.SAFE, ContentCategory.UNCERTAIN -> PolicyAction.ALLOW
                ContentCategory.REVEALING -> PolicyAction.WARN
                ContentCategory.EXPLICIT -> PolicyAction.BLOCK
            }
        }.let { action ->
            // A non-finite or zero-confidence result is uncertain, never silently safe.
            if (!confidence.isFinite()) PolicyAction.BLOCK else action
        }
    }
}
