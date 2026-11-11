import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version can be overridden from CI:
//   ./gradlew assembleRelease -PappVersionName=1.2.0 -PappVersionCode=10200
val appVersionName = (findProperty("appVersionName") as String?)?.removePrefix("v") ?: "1.0.0"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: run {
    val parts = appVersionName.split('.', '-').mapNotNull { it.toIntOrNull() }
    (parts.getOrElse(0) { 1 } * 10000) + (parts.getOrElse(1) { 0 } * 100) + parts.getOrElse(2) { 0 }
}

// Release signing comes from keystore.properties (local) or environment
// variables (CI). See docs/RELEASING.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "SIGNING_STORE_FILE")
val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).exists()

val rustDir = rootProject.layout.projectDirectory.dir("rust")
val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse("${System.getenv("LOCALAPPDATA")}\\Android\\Sdk\\ndk\\27.1.12297006")

val cargoBin = "${System.getProperty("user.home")}\\.cargo\\bin"

val buildRust by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compiles the Rust core for Android."
    workingDir = rustDir.asFile
    environment("ANDROID_NDK_HOME", ndkHome.get())
    environment("PATH", "$cargoBin;${System.getenv("PATH")}")
    commandLine(
        "$cargoBin\\cargo.exe", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", rootProject.layout.projectDirectory.dir("app/src/main/jniLibs").asFile.absolutePath,
        "build", "--release"
    )
    inputs.dir(rustDir.dir("src"))
    inputs.file(rustDir.file("Cargo.toml"))
    outputs.dir(rootProject.layout.projectDirectory.dir("app/src/main/jniLibs"))
}

android {
    namespace = "com.monochrome.annas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.monochrome.annas"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // A universal APK is built as well, so the release download works
            // on every device without the user picking an ABI.
            isUniversalApk = true
        }
    }

    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore the APK is signed with the debug key so it still installs.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/*.kotlin_module"
            )
        }
    }

    tasks.named("preBuild") { dependsOn(buildRust) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.material:material-icons-core:1.7.8")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
