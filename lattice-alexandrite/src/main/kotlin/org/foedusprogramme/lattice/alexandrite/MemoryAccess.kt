package org.foedusprogramme.lattice.alexandrite

import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult

/**
 * Who reaches an agent's memory, which every chat of the agent shares: the least a turn's actor must be for Lattice to
 * recall into the turn, capture from it, give it the period catalogue or run its tools in it ([LatticeConfig.access]).
 * An operator is one whose channel counts them an operator in the chat; a trusted user one it trusts there, or one named
 * in [ScenesConfig.trustedSenders]. A turn without an actor, such as a heartbeat, reaches only [EVERYONE].
 */
@Singleton
internal class MemoryAccess(config: LatticeConfig) {
    val level: String = config.access
    private val trustedSenders = config.scenes.trustedSenders.toSet()

    fun admits(turn: TurnInfo): Boolean = admits(turn.actor)

    fun admits(actor: ChatUser?): Boolean = when (level) {
        EVERYONE -> true
        TRUSTED -> actor != null && (actor.isOperator || actor.isTrusted || actor.address.toString() in trustedSenders)
        else -> actor?.isOperator == true
    }

    /** What a tool answers a turn that [admits] turns away, for [what] it would have done. */
    fun refusal(what: String): ToolResult {
        val who = if (level == TRUSTED) "the agent's operators and trusted users" else "the agent's operators"
        return ToolResult(
            "The long-term memory is open only to $who, and the person this turn answers is not one, so nothing was " +
                "$what. Answer without the memory.",
            isError = true,
        )
    }
}

/** Whether what [actor] brings into the memory is kept tainted: whatever anyone but an operator says or saves. */
internal fun taintsMemory(actor: ChatUser?): Boolean = actor?.isOperator != true

internal const val OPERATORS: String = "operators"
internal const val TRUSTED: String = "trusted"
internal const val EVERYONE: String = "everyone"
