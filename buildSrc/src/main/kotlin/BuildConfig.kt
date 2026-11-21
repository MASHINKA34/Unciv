@file:Suppress("InvalidPackageDeclaration")
package com.unciv.build

object BuildConfig {
    /** Used for desktop/server artifact file names (no spaces); the Android label is `app_name` in android/res */
    const val appName = "UncivOnline"
    const val appCodeNumber = 1286
    const val appVersion = "5.0.1"
    /** Android applicationId - differs from the official "com.unciv.app" so both can be installed side by side */
    const val identifier = "com.mashinka34.unciv"
    /** Android namespace (package of the generated R class) - stays the original so the sources need no changes */
    const val androidNamespace = "com.unciv.app"
}
