package com.turrit.assistant.automation

internal enum class AccountSelectionAction {
    SWIPE_TOWARD_TOP,
    SWITCH_TO_BOTTOM,
    SWIPE_TOWARD_BOTTOM,
    NOT_FOUND
}

/**
 * Bounded two-direction account lookup.
 * A repeated visible signature means the list reached that edge; it must not keep swiping forever.
 */
internal class AccountSelectionSearchState(
    private val maxTopSwipes: Int = 5,
    private val maxBottomSwipes: Int = 7
) {
    private enum class Phase { TOP, BOTTOM }

    private var phase = Phase.TOP
    private var swipes = 0
    private var previousSignature = ""
    private var stagnant = 0

    fun reset() {
        phase = Phase.TOP
        swipes = 0
        previousSignature = ""
        stagnant = 0
    }

    fun switchToBottom() {
        phase = Phase.BOTTOM
        swipes = 0
        previousSignature = ""
        stagnant = 0
    }

    fun observe(signature: String): AccountSelectionAction {
        val unchanged = swipes > 0 && signature == previousSignature
        stagnant = if (unchanged) stagnant + 1 else 0
        previousSignature = signature

        return when (phase) {
            Phase.TOP -> {
                if (swipes >= maxTopSwipes || stagnant >= 1) {
                    switchToBottom()
                    AccountSelectionAction.SWITCH_TO_BOTTOM
                } else {
                    swipes++
                    AccountSelectionAction.SWIPE_TOWARD_TOP
                }
            }
            Phase.BOTTOM -> {
                if (swipes >= maxBottomSwipes || stagnant >= 1) {
                    AccountSelectionAction.NOT_FOUND
                } else {
                    swipes++
                    AccountSelectionAction.SWIPE_TOWARD_BOTTOM
                }
            }
        }
    }
}
