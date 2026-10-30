package org.foedusprogramme.lattice.alexandrite

import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind

/** Whether Lattice recalls into and captures from this turn: a top-level turn that answers a message. */
internal fun TurnInfo.remembers(): Boolean = kind == TurnKind.MESSAGE && lineage == null

/** The chat that a graph files this turn's memories under. */
internal val TurnInfo.chatKey: String get() = key.chat.toString()
