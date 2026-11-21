
import com.unciv.build.AndroidImagePacker
import com.unciv.build.BuildConfig
import com.unciv.build.OnlineSecrets
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
}

// Unciv Online: release signing data lives in the local secrets file, see buildSrc OnlineSecrets
val onlineSecrets: Properties? = OnlineSecrets.load(rootDir)

android {
    compileSdk = 37
    sourceSets {
        getByName("main").apply {
            manifest.srcFile("AndroidManifest.xml")
            java.directories += "src"
            kotlin.directories += "src"
            aidl.directories += "src"
            res.directories += "res"
            assets.directories += "assets"
            jniLibs.directories += "libs"
        }
    }
    packaging {
        resources.excludes += "META-INF/robovm/ios/robovm.xml"
        // part of kotlinx-coroutines-android, should not go into the apk
        resources.excludes += "DebugProbesKt.bin"
    }
    defaultConfig {
        // The namespace decides the package of the generated R class - keep the original one,
        // only the applicationId changes so Unciv Online installs next to the official Unciv.
        namespace = BuildConfig.androidNamespace
        applicationId = BuildConfig.identifier
        minSdk = 21
        targetSdk = 36
        versionCode = BuildConfig.appCodeNumber
        versionName = BuildConfig.appVersion

        base.archivesName.set(BuildConfig.appName)
    }

    // Had to add this crap for Travis to build, it wanted to sign the app
    // but couldn't create the debug keystore for some reason

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("android/debug.keystore")
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storePassword = "android"
        }
        // Unciv Online release key: path and passwords come from the local secrets file (never committed)
        if (onlineSecrets != null) create("release") {
            storeFile = file(onlineSecrets.getProperty("release.storeFile"))
            storePassword = onlineSecrets.getProperty("release.storePassword")
            keyAlias = onlineSecrets.getProperty("release.keyAlias")
            keyPassword = onlineSecrets.getProperty("release.keyPassword")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            // If you make this true you get a version of the game that just flat-out doesn't run
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        disable += "MissingTranslation"   // see res/values/strings.xml
    }
    compileOptions {
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }
    androidResources {
        // Don't add local save files and fonts to release, obviously
        ignoreAssetsPattern = "!SaveFiles:!fonts:!maps:!music:!mods"
    }
    buildFeatures {
        aidl = true
    }
}

// necessary for Android WorkManager lib, used in MultiplayerTurnCheckWorker
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

tasks.register("texturePacker") {
    doFirst {
        logger.info("Calling TexturePacker")
        AndroidImagePacker.packImages(projectDir.path)
    }
}

// called every time gradle gets executed, takes the native dependencies of
// the natives configuration, and extracts them to the proper libs/ folders
// so they get packed with the APK.
tasks.register("copyAndroidNatives") {
    val natives: Configuration by configurations

    doFirst {
        val rx = Regex(""".*natives-([^.]+)\.jar$""")
        natives.forEach { jar ->
            if (rx.matches(jar.name)) {
                val outputDir = file(rx.replace(jar.name) { "libs/" + it.groups[1]!!.value })
                outputDir.mkdirs()
                copy {
                    from(zipTree(jar))
                    into(outputDir)
                    include("*.so")
                }
            }
        }
    }
    dependsOn("texturePacker")
}

// A release APK without our key and server settings would be useless for friends - fail early and clearly
tasks.register("checkOnlineReleaseSecrets") {
    val secretsFound = onlineSecrets != null
    val missing = listOf("server.url", "shared.key", "release.storeFile", "release.storePassword", "release.keyAlias", "release.keyPassword")
        .filter { onlineSecrets?.getProperty(it).isNullOrBlank() }
    val lookup = OnlineSecrets.describeLookup(rootDir)
    doFirst {
        if (!secretsFound) throw GradleException("Unciv Online release build needs the secrets file: $lookup")
        if (missing.isNotEmpty()) throw GradleException("Unciv Online secrets file is missing keys $missing ($lookup)")
        logger.lifecycle("Unciv Online release secrets OK ($lookup)")
    }
}

tasks.whenTaskAdded {
    // See https://github.com/yairm210/Unciv/issues/4842
    if ("package" in name || "assemble" in name || "bundleRelease" in name) {
        dependsOn("copyAndroidNatives")
    }
    if (name == "packageRelease" || name == "bundleRelease") {
        dependsOn("checkOnlineReleaseSecrets")
    }
}

private fun getSdkPath(): String? {
    val localProperties = project.file("../local.properties")
    return if (localProperties.exists()) {
        val properties = Properties()
        localProperties.inputStream().use { properties.load(it) }

        properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    } else {
        System.getenv("ANDROID_HOME")
    }
}

tasks.register<Exec>("run") {
    standardOutput = System.out
    errorOutput = System.err
    isIgnoreExitValue = false

    val path = getSdkPath()
    val adb = "$path/platform-tools/adb"

    commandLine(adb, "shell", "am", "start", "-n", "${BuildConfig.identifier}/com.unciv.app.AndroidLauncher")
}

dependencies {
    implementation(libs.android.ktx.core)
    implementation(libs.android.ktx.runtime)
    // Needed to convert e.g. Android 26 API calls to Android 21
    // If you remove this run `./gradlew :android:lintDebug` to ensure everything's okay.
    // If you want to upgrade this, check it's working by building an apk,
    //   or by running `./gradlew :android:assembleRelease` which does that
    coreLibraryDesugaring(libs.android.desugar)
}
