package com.unciv.online

import com.unciv.utils.Tag

/**
 * Unciv Online: log tags of our own game mechanics, so each one can be filtered in logcat
 * (`adb logcat -s UncivOnline UncivRepeatable UncivDevPanel UncivDebugWorld`).
 * [com.unciv.utils.Log.info] and [com.unciv.utils.Log.warn] are written in release builds too.
 */
object OnlineLogTags {
    /** Repeatable buildings ([com.unciv.models.ruleset.unique.UniqueType.Repeatable]): copies added and removed */
    val repeatable = Tag("UncivRepeatable")
    /** Developer mode: panel actions, console commands, developer resource supply */
    val devPanel = Tag("UncivDevPanel")
    /** Developer mode: setup of the "debug world" at game start */
    val debugWorld = Tag("UncivDebugWorld")
}
