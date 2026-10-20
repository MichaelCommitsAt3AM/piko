package com.piko.agent.core

import com.piko.agent.model.ScreenObservation

/** Fail closed when the screen changes while the model or user is deciding. */
object ActionContextGuard {
    fun isCurrent(before: ScreenObservation, fresh: ScreenObservation): Boolean =
        before.rootAvailable && fresh.rootAvailable &&
            before.foregroundPackage != null &&
            before.foregroundPackage == fresh.foregroundPackage &&
            before.screenWidth == fresh.screenWidth &&
            before.screenHeight == fresh.screenHeight &&
            before.rotation == fresh.rotation &&
            before.windowTitle == fresh.windowTitle &&
            before.hasDialog == fresh.hasDialog &&
            before.focusedElementId == fresh.focusedElementId &&
            before.elements == fresh.elements
}
