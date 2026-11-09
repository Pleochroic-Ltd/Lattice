package org.foedusprogramme.lattice

/**
 * What the host tells Lattice about whose words and which tool results a scene's content can be trusted with. A scene
 * is untrusted when one of its messages is [ConversationMessage.tainted], comes from a sender or a host source this
 * policy does not trust, or is the result of a tool it does not trust or that replayed stored conversation text; or
 * when it advances or closes a thread an untrusted scene opened. Periods and their catalogue name trusted scenes only.
 *
 * The default, [Off], judges no sender, tool or host text: only a message the host marked tainted makes a scene
 * untrusted. [Conservative] trusts nothing it is not told about: until a host names its owners and its local,
 * read-only tools, every scene with a person's message or a tool result in it is untrusted.
 */
interface SceneTrustPolicy {
    /** False: the policy is off, and a scene is untrusted only through a [ConversationMessage.tainted] message. */
    val enforced: Boolean get() = true

    /** [senderId] ([ConversationMessage.senderId] of a person's message) is someone the graph's owner trusts in [chatKey]. */
    fun isTrustedSender(chatKey: String, senderId: String): Boolean

    /** A result of [toolName], called with [arguments], brings no outside content in: a local, read-only tool. */
    fun isTrustedTool(toolName: String, arguments: String?): Boolean

    /**
     * A call of [toolName] with [arguments] replayed stored conversation text (a transcript tool, a timeline page of
     * original messages): that text could be anything, so such a result is never trusted, whatever [isTrustedTool] says.
     */
    fun opensRawText(toolName: String, arguments: String?): Boolean

    /** Text the host wrote itself ([ConversationMessage.Role.HOST]) from [source] that brings nothing untrusted in. */
    fun isTrustedHostText(source: String?): Boolean

    companion object {
        /** Judges nothing: every sender, tool and host text is trusted, and only tainted messages count. */
        val Off: SceneTrustPolicy = object : SceneTrustPolicy {
            override val enforced: Boolean get() = false

            override fun isTrustedSender(chatKey: String, senderId: String): Boolean = true

            override fun isTrustedTool(toolName: String, arguments: String?): Boolean = true

            override fun opensRawText(toolName: String, arguments: String?): Boolean = false

            override fun isTrustedHostText(source: String?): Boolean = true

            override fun toString(): String = "SceneTrustPolicy.Off"
        }

        /** Trusts no sender, no tool and no host text. */
        val Conservative: SceneTrustPolicy = NamedSceneTrustPolicy()
    }
}

/**
 * A [SceneTrustPolicy] from lists: the trusted senders (the same in every chat), the trusted tools, the tools whose
 * results replay stored conversation text, and the trusted host sources. A [rawTextTools] entry wins over
 * [trustedTools].
 */
data class NamedSceneTrustPolicy(
    val trustedSenders: Set<String> = emptySet(),
    val trustedTools: Set<String> = emptySet(),
    val rawTextTools: Set<String> = emptySet(),
    val trustedHostSources: Set<String> = emptySet(),
) : SceneTrustPolicy {
    override fun isTrustedSender(chatKey: String, senderId: String): Boolean = senderId in trustedSenders

    override fun isTrustedTool(toolName: String, arguments: String?): Boolean = toolName in trustedTools

    override fun opensRawText(toolName: String, arguments: String?): Boolean = toolName in rawTextTools

    override fun isTrustedHostText(source: String?): Boolean = source != null && source in trustedHostSources
}
