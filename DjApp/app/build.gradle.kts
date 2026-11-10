import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.djapp"
    compileSdk = 34
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "com.maxido.dj"
        minSdk = 26; targetSdk = 34; versionCode = (System.getenv("VERSION_CODE") ?: "5").toInt(); versionName = System.getenv("VERSION_NAME") ?: "0.5"
        externalNativeBuild { cmake { cppFlags += "-std=c++17"; arguments += "-DANDROID_STL=c++_shared" } }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    // SPEED: a debuggable, un-shrunk build runs Jetpack Compose several times slower, especially on budget phones.
    // Both build types are therefore built like a real release (not debuggable, code shrunk / optimised), signed with the
    // debug key so the existing "assembleDebug" workflow still produces an APK that installs directly.
    // RELEASE SIGNING: with the KEYSTORE_* secrets set in GitHub, every build is signed with YOUR permanent key, so a new
    // version installs over the old one. Without them it falls back to the debug key (fine for tests, not for customers).
    signingConfigs {
        create("maxido") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (!ks.isNullOrEmpty() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (!System.getenv("KEYSTORE_FILE").isNullOrEmpty()) signingConfigs.getByName("maxido") else signingConfigs.getByName("debug")
        }
    }
    // The release build has no lint gate: it must always produce an installable APK.
    lint { checkReleaseBuilds = false; abortOnError = false }
    buildFeatures { compose = true; prefab = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { pickFirsts += "**/libc++_shared.so" } }
    // Keep the bundled AI model uncompressed: faster build, and the app can read its exact size.
    androidResources { noCompress += listOf("onnx", "mp3", "ogg") }
}

// ---- Bundle the SCNet models so AI stems work fully offline -----------------------------------------------
// Two int8 ONNX models (MIT licence, audiojs on Hugging Face) are downloaded ONCE at build time into src/main/assets
// (git-ignored) and packed into the APK: a small one (~13 MB) for budget phones and a large one (~45 MB) for phones
// with 6 GB+ RAM. The phone never needs internet. A copy you already have can simply be put there with that name.
fun sha256Of(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { i ->
        val b = ByteArray(1 shl 16)
        while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
// file name, download address, SHA-256, minimum plausible size
val scnetModels = listOf(
    listOf("scnet.int8.onnx", "https://huggingface.co/audiojs/scnet/resolve/main/scnet.int8.onnx",
        "98228931494151762a1c4ab1ec7899a894b1f81fd4a509921a9d2f9bacc50845", "5000000"),
    listOf("scnet-large.int8.onnx", "https://huggingface.co/audiojs/scnet-large/resolve/main/scnet-large.int8.onnx",
        "b2dc586a1e0e6c0afe4915e9057ea29111397b7bbf589edc3d85de91de79cd72", "20000000")
)
val assetsDir = layout.projectDirectory.dir("src/main/assets").asFile
val fetchScnetModel by tasks.registering {
    description = "Downloads the SCNet ONNX models into src/main/assets so they are packed into the APK."
    outputs.dir(assetsDir)
    doLast {
        assetsDir.mkdirs()
        for (m in scnetModels) {
            val target = File(assetsDir, m[0])
            if (target.exists() && target.length() >= m[3].toLong()) continue
            val part = File(assetsDir, m[0] + ".part")
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    val conn = URL(m[1]).openConnection() as HttpURLConnection
                    conn.connectTimeout = 30_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = true
                    conn.inputStream.use { input -> part.outputStream().use { out -> input.copyTo(out) } }
                    check(part.length() >= m[3].toLong()) { "downloaded file is too small (${part.length()} bytes)" }
                    val got = sha256Of(part)
                    check(got == m[2]) { "checksum mismatch (got $got)" }
                    target.delete()
                    check(part.renameTo(target)) { "could not move model into place" }
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    part.delete()
                    println("SCNet model ${m[0]} download attempt $attempt failed: ${e.message}")
                }
            }
            lastError?.let { throw GradleException("Could not download the SCNet model ${m[0]}: ${it.message}", it) }
            println("SCNet model ready: ${m[0]} ${target.length()} bytes, sha256=" + sha256Of(target))
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchScnetModel) }
dependencies {
    implementation("com.google.oboe:oboe:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    // 480p video output: converts big videos once into a light 480p copy (see VideoProxy.kt)
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
}
