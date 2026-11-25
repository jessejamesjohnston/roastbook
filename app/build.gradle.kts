import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Release signing inputs, in order: local.properties, then Gradle properties, then environment
// variables (scripts/release.sh passes them this way so no secret is on a command line). CI never
// signs a release and never sees a key. Nothing here is committed; keystores are gitignored.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingProp(name: String): String? =
    localProps.getProperty("roastbook.keystore.$name")
        ?: providers.gradleProperty("roastbook.keystore.$name").orNull
        ?: System.getenv("ROASTBOOK_KEYSTORE_" + name.uppercase())

kotlin {
    compilerOptions {
        // Opt in now to the annotation target Kotlin will default to: an annotation on a
        // constructor property (such as Hilt's @ApplicationContext) applies to the parameter and
        // the property, which is what the code means. Without it every such site warns.
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
        // Zero warnings is the bar, in main and test code: a new compiler warning fails the build.
        // A suppression names its reason beside it.
        allWarningsAsErrors.set(true)
    }
}

android {
    namespace = "dev.roastbook.app"
    compileSdk = 37

    defaultConfig {
        // Publisher: Merriment Labs. Permanent once distributed. The Kotlin namespace above stays
        // dev.roastbook.app; it names the code's packages, not the installed app.
        applicationId = "com.merrimentlabs.roastbook"
        minSdk = 31
        targetSdk = 36
        versionCode = 8
        versionName = "0.3.5"
    }

    signingConfigs {
        create("release") {
            val storePath = signingProp("path")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = signingProp("storePassword")
                keyAlias = signingProp("keyAlias")
                keyPassword = signingProp("keyPassword")
            }
        }
    }

    val releaseKeystorePresent = signingConfigs.getByName("release").storeFile != null
    val allowDebugSigning = providers.gradleProperty("allowDebugSigning").orNull == "true"

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The debug fallback only lets the variant CONFIGURE on a keystore-less machine so
            // debug/test tasks stay green; whether a release artifact may be BUILT with it is
            // gated at task time below.
            signingConfig = if (releaseKeystorePresent) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // Fail-closed release signing, so a debug-signed "release" can never slip out unnoticed: the
    // first task of the release chain refuses unless the keystore is present or the developer
    // explicitly asked for a debug-signed R8 test build.
    tasks.configureEach {
        if (name == "preReleaseBuild" && !releaseKeystorePresent) {
            doFirst {
                if (allowDebugSigning) {
                    logger.warn("WARNING: release keystore missing; building release with DEBUG signing because -PallowDebugSigning=true. Not shippable.")
                } else {
                    error("release signing requires roastbook.keystore.* (local.properties, -P, or ROASTBOOK_KEYSTORE_* env); pass -PallowDebugSigning=true for a local R8 test build")
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Zero warnings is the bar: a new lint warning fails the build. A check that is switched off
        // or suppressed says why where it is (app/lint.xml, or a comment beside the suppression).
        warningsAsErrors = true
        abortOnError = true
        lintConfig = file("lint.xml")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.androidx.palette)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.icons.extended)

    implementation(libs.room.runtime)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.paging.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.coil.compose)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.subject.segmentation)
    implementation(libs.mlkit.genai.prompt)
    // The downloadable on-device reader (Gemma via LiteRT-LM). Ships native code for arm64-v8a and
    // x86_64 only; other ABIs keep working and simply never offer the reader.
    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.paging.testing)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}

// Lint's unit-test analysis reads the test sources KSP generates (Hilt's test components) without
// waiting for them. Run in the same build while KSP rewrites them, it fails inside the Kotlin
// analyzer ("Unexpected failure during lint analysis"); ordering it after KSP avoids that.
tasks.configureEach {
    if (name == "lintAnalyzeDebugUnitTest") mustRunAfter("kspDebugUnitTestKotlin")
}

// Robolectric loads app classes through its own sandbox classloader; without this JaCoCo's agent
// skips them and every Robolectric-tested class reports 0%. AGP attaches the extension late, hence
// afterEvaluate.
afterEvaluate {
    tasks.withType<Test>().configureEach {
        extensions.findByType<JacocoTaskExtension>()?.apply {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }
}
