plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun stringPropertyOrEnv(propertyName: String, envName: String, fallback: String? = null): String? {
    val propertyValue = (project.findProperty(propertyName) as String?)?.takeIf { it.isNotBlank() }
    val envValue = System.getenv(envName)?.takeIf { it.isNotBlank() }
    return propertyValue ?: envValue ?: fallback
}

fun inferRuntimeVersion(path: String?): String {
    if (path.isNullOrBlank()) return "embedded-missing"
    return file(path).name
        .removeSuffix(".tar.gz")
        .removeSuffix(".tgz")
        .removeSuffix(".zip")
        .ifBlank { "embedded-dev" }
}

val localDebugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
val sharedSigningKeystorePath = stringPropertyOrEnv(
    propertyName = "codexMobile.keystore.path",
    envName = "CODEX_MOBILE_KEYSTORE_PATH",
    fallback = localDebugKeystore.takeIf { it.exists() }?.absolutePath,
)
val sharedSigningKeystoreFile = sharedSigningKeystorePath?.let(::file)
val sharedSigningEnabled = sharedSigningKeystoreFile?.exists() == true
val sharedSigningStorePassword = stringPropertyOrEnv(
    propertyName = "codexMobile.keystore.password",
    envName = "CODEX_MOBILE_KEYSTORE_PASSWORD",
    fallback = "android",
)!!
val sharedSigningKeyAlias = stringPropertyOrEnv(
    propertyName = "codexMobile.key.alias",
    envName = "CODEX_MOBILE_KEY_ALIAS",
    fallback = "androiddebugkey",
)!!
val sharedSigningKeyPassword = stringPropertyOrEnv(
    propertyName = "codexMobile.key.password",
    envName = "CODEX_MOBILE_KEY_PASSWORD",
    fallback = "android",
)!!

val runtimeArchivePath = stringPropertyOrEnv(
    propertyName = "codexMobile.runtime.archive",
    envName = "CODEX_MOBILE_RUNTIME_ARCHIVE",
)
val runtimeArchiveFile = runtimeArchivePath?.let(::file)?.takeIf { it.exists() }
val runtimePackaged = runtimeArchiveFile != null
val runtimeVersion = stringPropertyOrEnv(
    propertyName = "codexMobile.runtime.version",
    envName = "CODEX_MOBILE_RUNTIME_VERSION",
    fallback = inferRuntimeVersion(runtimeArchivePath),
)!!
val generatedRuntimeAssetsDir = layout.buildDirectory.dir("generated/codexRuntimeAssets")
val prepareCodexRuntimeAssets by tasks.registering {
    notCompatibleWithConfigurationCache("Uses Ant/resource archive transforms for optional embedded runtime packaging.")
    outputs.dir(generatedRuntimeAssetsDir)
    inputs.property("runtimePackaged", runtimePackaged)
    inputs.property("runtimeVersion", runtimeVersion)
    runtimeArchiveFile?.let {
        if (it.isDirectory) {
            inputs.dir(it)
        } else {
            inputs.file(it)
        }
    }

    doLast {
        val outputRoot = generatedRuntimeAssetsDir.get().asFile
        val runtimeDir = outputRoot.resolve("runtime")
        outputRoot.deleteRecursively()
        runtimeDir.mkdirs()

        val source = runtimeArchiveFile ?: return@doLast
        val stagingDir = temporaryDir.resolve("embedded-runtime").apply {
            deleteRecursively()
            mkdirs()
        }

        when {
            source.isDirectory -> copy {
                from(source)
                into(stagingDir)
            }
            source.name.endsWith(".zip", ignoreCase = true) -> copy {
                from(zipTree(source))
                into(stagingDir)
            }
            source.name.endsWith(".tar.gz", ignoreCase = true) || source.name.endsWith(".tgz", ignoreCase = true) -> copy {
                from(tarTree(resources.gzip(source)))
                into(stagingDir)
            }
            else -> throw GradleException("Unsupported runtime archive: ${source.absolutePath}")
        }

        ant.withGroovyBuilder {
            "zip"(
                "destfile" to runtimeDir.resolve("codex-runtime-arm64.zip").absolutePath,
                "basedir" to stagingDir.absolutePath,
            )
        }
    }
}

android {
    namespace = "io.github.aeewws.codexmobile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        buildConfigField("boolean", "CODEX_RUNTIME_PACKAGED", runtimePackaged.toString())
        buildConfigField("String", "CODEX_RUNTIME_VERSION", "\"$runtimeVersion\"")
        buildConfigField("String", "CODEX_RUNTIME_ASSET", "\"runtime/codex-runtime-arm64.zip\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("legacy") {
            dimension = "distribution"
            applicationId = "com.example.myapplication"
        }
        create("oss") {
            dimension = "distribution"
            applicationId = "io.github.aeewws.codexmobile"
        }
    }

    if (sharedSigningEnabled) {
        signingConfigs {
            create("sharedCompat") {
                storeFile = sharedSigningKeystoreFile!!
                storePassword = sharedSigningStorePassword
                keyAlias = sharedSigningKeyAlias
                keyPassword = sharedSigningKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (sharedSigningEnabled) {
                signingConfig = signingConfigs.getByName("sharedCompat")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (sharedSigningEnabled) {
                signingConfig = signingConfigs.getByName("sharedCompat")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").assets.srcDir(generatedRuntimeAssetsDir.get().asFile)
}

tasks.named("preBuild") {
    dependsOn(prepareCodexRuntimeAssets)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
