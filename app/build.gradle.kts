import java.io.FileInputStream
import java.util.Properties
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipOutputStream

val keystorePropertiesFile = rootProject.file("keystore.properties")
val useKeystoreProperties = keystorePropertiesFile.canRead()
val keystoreProperties = Properties()
if (useKeystoreProperties) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
}

// Resolve the two pinned inputs without their optional delivery/accelerator dependency graph.
// Package the tested CPU Interpreter classes and native libraries, with the upstream notices.
val portraitRuntimeInputs = configurations.create("portraitRuntimeInputs") {
    isCanBeConsumed = false
    isTransitive = false
}
val portraitGpuInputs = configurations.create("portraitGpuInputs") {
    isCanBeConsumed = false
    isTransitive = false
}
val evaluatePortraitGpu = providers.gradleProperty("cameraGpuBenchmark").orNull == "true"
val portraitRuntimeDir = layout.buildDirectory.dir("generated/portrait-runtime")
val preparePortraitRuntime = tasks.register("preparePortraitRuntime") {
    inputs.files(portraitRuntimeInputs)
    outputs.dir(portraitRuntimeDir)
    doLast {
        val expected = mapOf(
            "litert-2.3.0.aar" to "9508c6e9142646f0f2bc1fe68b3925fb470ee7ddd29114540339a04a8ab189dc",
            "litert-api-2.3.0.aar" to "20f630e9ed0b4f0564da616fd3d6406f3013cacad7d492cbe2963330f519256b",
        )
        val inputs = portraitRuntimeInputs.resolve().sortedBy { it.name }
        check(inputs.map { it.name }.toSet() == expected.keys) { "Unexpected Portrait runtime inputs" }
        val output = portraitRuntimeDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val classes = sortedMapOf<String, ByteArray>()
        val compiledClasses = sortedMapOf<String, ByteArray>()
        for (input in inputs) {
            val sha = MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            check(sha == expected[input.name]) { "Portrait runtime digest mismatch" }
            ZipFile(input).use { archive ->
                ZipInputStream(archive.getInputStream(archive.getEntry("classes.jar"))).use { jar ->
                    while (true) {
                        val entry = jar.nextEntry ?: break
                        if (entry.name.startsWith("org/tensorflow/lite/") && entry.name.endsWith(".class")) {
                            check(classes.put(entry.name, jar.readBytes()) == null) { "Duplicate Interpreter class" }
                        }
                        if (entry.name.startsWith("com/google/ai/edge/litert/") && entry.name.endsWith(".class") &&
                            !entry.name.startsWith("com/google/ai/edge/litert/deployment/") &&
                            !entry.name.startsWith("com/google/ai/edge/litert/ModelProvider") &&
                            !entry.name.startsWith("com/google/ai/edge/litert/ModelSelector")) {
                            check(compiledClasses.put(entry.name, jar.readBytes()) == null)
                        }
                    }
                }
                for (entry in archive.entries()) {
                    if (entry.name.matches(Regex("jni/(arm64-v8a|armeabi-v7a|x86_64)/(libLiteRt|liblitert_jni)\\.so"))) {
                        val target = output.resolve("jniLibs/" + entry.name.removePrefix("jni/"))
                        target.parentFile.mkdirs()
                        archive.getInputStream(entry).use { target.writeBytes(it.readBytes()) }
                    }
                }
                for (name in listOf("LICENSE", "THIRD_PARTY_NOTICE.txt")) {
                    val target = output.resolve("assets/licenses/" + input.name.removeSuffix(".aar") + "-" + name)
                    target.parentFile.mkdirs()
                    archive.getInputStream(archive.getEntry(name)).use { target.writeBytes(it.readBytes()) }
                }
            }
        }
        check(classes.size == 27) { "Pinned CPU Interpreter class set changed" }
        ZipOutputStream(output.resolve("portrait-cpu.jar").outputStream()).use { jar ->
            for ((name, bytes) in classes) {
                val entry = ZipEntry(name).apply { time = 0 }
                jar.putNextEntry(entry)
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        ZipOutputStream(output.resolve("portrait-compiled-api.jar").outputStream()).use { jar ->
            for ((name, bytes) in compiledClasses) {
                jar.putNextEntry(ZipEntry(name).apply { time = 0 }); jar.write(bytes); jar.closeEntry()
            }
        }

    }
}
// The separate preview package needs the pinned GPU accelerator in release too.
// Camera release keeps its CPU-only runtime; evaluation code may reuse this output.
val portraitGpuRuntimeDir = layout.buildDirectory.dir("generated/portrait-gpu-runtime")
val preparePortraitGpuRuntime = tasks.register("preparePortraitGpuRuntime") {
    inputs.files(portraitGpuInputs)
    outputs.dir(portraitGpuRuntimeDir)
    doLast {
        val gpu = portraitGpuInputs.singleFile
        check(gpu.name == "litert-gpu-2.3.0.aar")
        val digest = MessageDigest.getInstance("SHA-256").digest(gpu.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == "173a4c3813601f7e9542f4e6a49e237775e2f9ce1e0948f35407098bffb1824f")
        val output = portraitGpuRuntimeDir.get().asFile
        output.deleteRecursively(); output.mkdirs()
        val abis = mutableSetOf<String>()
        ZipFile(gpu).use { archive ->
            for (entry in archive.entries()) {
                if (entry.name.matches(Regex("jni/(arm64-v8a|armeabi-v7a|x86_64)/libLiteRtClGlAccelerator\\.so"))) {
                    abis.add(entry.name.split('/')[1])
                    val target = output.resolve("jniLibs/" + entry.name.removePrefix("jni/"))
                    target.parentFile.mkdirs(); archive.getInputStream(entry).use { target.writeBytes(it.readBytes()) }
                }
            }
            check(abis == setOf("arm64-v8a", "armeabi-v7a", "x86_64")) { "Pinned GPU ABI set changed" }
            for (name in listOf("LICENSE", "THIRD_PARTY_NOTICE.txt")) {
                val target = output.resolve("assets/licenses/litert-gpu-2.3.0-$name")
                target.parentFile.mkdirs(); archive.getInputStream(archive.getEntry(name)).use { target.writeBytes(it.readBytes()) }
            }
        }
    }
}
if (evaluatePortraitGpu) tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(preparePortraitGpuRuntime) }
val portraitRuntimeJar = files(portraitRuntimeDir.map { it.file("portrait-cpu.jar") }).builtBy(preparePortraitRuntime)
val portraitCompiledApiJar = files(portraitRuntimeDir.map { it.file("portrait-compiled-api.jar") }).builtBy(preparePortraitRuntime)
tasks.named("preBuild").configure { dependsOn(preparePortraitRuntime) }

// The depth model is checked unpacked (export_depth.py). Its gzip bytes may vary with zlib.
// Not named .gz: the Android Gradle plugin would unpack that into an uncompressed asset.
val verifyPortraitModels = tasks.register("verifyPortraitModels") {
    val expected = mapOf(
        "portrait_subject_v1.tflite" to (6_227_884L to "e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25"),
        "portrait_depth_v2_small.tflite.gzip" to (98_814_896L to "72ba875d48c520c3ced0cd10782f81fab5764c24c34ee7620e9d3e075a866de5"),
    )
    val models = expected.keys.map { file("src/main/assets/$it") }
    inputs.files(models)
    doLast {
        for (model in models) {
            val pin = expected.getValue(model.name)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            model.inputStream().buffered().let { if (model.name.endsWith(".gzip")) GZIPInputStream(it) else it }.use { stream ->
                val buffer = ByteArray(1024 * 1024)
                while (total <= pin.first) {
                    val count = stream.read(buffer); if (count < 0) break
                    digest.update(buffer, 0, count); total += count
                }
            }
            check(total == pin.first) { "Portrait model size mismatch" }
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == pin.second) { "Portrait model digest mismatch" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyPortraitModels) }

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

android {
    if (useKeystoreProperties) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV4Signing = true
            }

            create("play") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["uploadKeyAlias"] as String
                keyPassword = keystoreProperties["uploadKeyPassword"] as String
            }
        }
    }

    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"
    androidResources.noCompress += "tflite"

    namespace = "app.grapheneos.camera"

    defaultConfig {
        applicationId = "app.grapheneos.camera"
        // DiamaneOS: the Camera's own preview processor app (module processor).
        manifestPlaceholders["portraitProcessorPackage"]="de.diamaneos.camera.processor"
        buildConfigField("String","PORTRAIT_PROCESSOR_PACKAGE","\"de.diamaneos.camera.processor\"")
        minSdk = 29
        targetSdk = 37
        // DiamaneOS: upstream version x 100 + DiamaneOS revision (93.2 = 9302). Always higher than
        // upstream's own numbers and only ever rising, as Android refuses a lower version as an update.
        versionCode = 9306
        versionName = "93.6"

        buildConfigField("boolean", "CAMERA_NEURAL_DENOISE_TEST", "false")
        buildConfigField("boolean", "CAMERA_JPEG_BURST_EVALUATION",
            (providers.gradleProperty("cameraJpegBurstEvaluation").orNull == "true").toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            // Release ships for arm64 only; debug keeps the emulator and 32-bit ABIs.
            ndk.abiFilters += "arm64-v8a"
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
            resValue("string", "app_name", "Camera")
        }

        getByName("debug") {
            ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            buildConfigField("boolean", "CAMERA_NEURAL_DENOISE_TEST", (providers.gradleProperty("cameraNeuralDenoiseTest").orNull=="true").toString())
            applicationIdSuffix = ".dev"
            manifestPlaceholders["portraitProcessorPackage"]="de.diamaneos.camera.processor.dev"
            buildConfigField("String","PORTRAIT_PROCESSOR_PACKAGE","\"de.diamaneos.camera.processor.dev\"")
            proguardFiles("src/debug/gpu-proguard.pro")
            resValue("string", "app_name", "Camera d")
            if(providers.gradleProperty("cameraMinifyDebug").orNull=="true") {
                isMinifyEnabled=true;isShrinkResources=true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
            }
            providers.gradleProperty("cameraDefaultPreviewFps").orNull?.toInt()?.let {fps ->
                require(fps in 0..60);resValue("integer","config_camera_default_preview_fps",fps.toString())
            }
            providers.gradleProperty("cameraPreviewNoiseReduction").orNull?.toInt()?.let {mode ->
                require(mode in -1..4);resValue("integer","config_camera_preview_noise_reduction",mode.toString())
            }
            providers.gradleProperty("cameraPreviewTemporalRetention").orNull?.toInt()?.let {value ->
                require(value in 0..80);resValue("integer","config_camera_preview_temporal_retention",value.toString())
            }
            providers.gradleProperty("cameraVideoPreviewLuma").orNull?.toInt()?.let {value ->
                require(value in 0..100);resValue("integer","config_camera_video_preview_luma_denoise",value.toString())
            }
            providers.gradleProperty("cameraJpegHqNoiseReduction").orNull?.let {enabled ->
                require(enabled in listOf("true","false"));resValue("bool","config_camera_jpeg_high_quality_noise_reduction",enabled)
            }
            providers.gradleProperty("cameraEdgeAwareDemosaic").orNull?.let {enabled ->
                require(enabled in listOf("true","false"));resValue("bool","config_camera_edge_aware_demosaic",enabled)
            }
            providers.gradleProperty("cameraUltraWideSplitRawJpeg").orNull?.let {enabled ->
                require(enabled in listOf("true","false"));resValue("bool","config_camera_ultra_wide_split_raw_jpeg",enabled)
            }
            providers.gradleProperty("cameraNightIspJpeg").orNull?.let {enabled ->
                require(enabled in listOf("true","false"));resValue("bool","config_camera_night_isp_jpeg",enabled)
            }
            providers.gradleProperty("cameraNightFrequencyDenoise").orNull?.let {enabled ->
                require(enabled in listOf("true","false"));resValue("bool","config_camera_night_frequency_denoise",enabled)
            }
            providers.gradleProperty("cameraUltraWideRawGrid").orNull?.let {profile ->
                require(profile.length<=96 && profile.matches(Regex("[0-9]+(,[0-9]+){6}")))
                resValue("string","config_camera_ultra_wide_raw_correction_grid",profile)
            }
            providers.gradleProperty("cameraUltraWideJpegDenoise").orNull?.let { enabled ->
                require(enabled in listOf("true", "false"))
                resValue("bool", "config_camera_ultra_wide_jpeg_denoise", enabled)
            }
            providers.gradleProperty("cameraUltraWideJpegExposure").orNull?.let { enabled ->
                require(enabled in listOf("true", "false"))
                resValue("bool", "config_camera_ultra_wide_jpeg_exposure_compensation", enabled)
            }
            providers.gradleProperty("cameraUltraWideBurst").orNull?.let { enabled ->
                require(enabled in listOf("true", "false"))
                resValue("bool", "config_camera_ultra_wide_burst", enabled)
            }
            providers.gradleProperty("cameraMainRawBurst").orNull?.let { enabled ->
                require(enabled in listOf("true", "false"))
                resValue("bool", "config_camera_main_raw_burst", enabled)
            }
            providers.gradleProperty("cameraDirectRawBurst").orNull?.let { enabled ->
                require(enabled in listOf("true", "false"))
                resValue("bool", "config_camera_direct_raw_burst", enabled)
            }
            providers.gradleProperty("cameraPhotoSteadyExposure").orNull?.let { enabled ->
                require(enabled in listOf("true", "false"))
                resValue("bool", "config_camera_photo_steady_exposure", enabled)
            }
            providers.gradleProperty("cameraPhysicalMain").orNull?.let { enabled ->
                require(enabled in listOf("true", "false"))
                resValue("bool", "config_camera_prefer_physical_main", enabled)
            }
            providers.gradleProperty("cameraPreviewShortEdge").orNull?.toInt()?.let { edge ->
                require(edge in 0..2160)
                resValue("integer", "config_camera_preview_target_short_edge", edge.toString())
            }
            providers.gradleProperty("cameraNightUltraWideHighlightLevel").orNull?.toInt()?.let { value ->
                require(value in 200..255);resValue("integer","config_camera_night_ultra_wide_highlight_level",value.toString())
            }
            for ((property,resource) in listOf("cameraNightIspJpegMs" to "config_camera_night_isp_jpeg_max_exposure_ms",
                "cameraNightHandheldMs" to "config_camera_night_handheld_max_exposure_ms",
                "cameraNightSteadyMs" to "config_camera_night_steady_max_exposure_ms")) {
                providers.gradleProperty(property).orNull?.toInt()?.let { value ->
                    require(value in 0..1000);resValue("integer",resource,value.toString())
                }
            }
            // isDebuggable = false
        }

        create("play") {
            initWith(getByName("release"))
            applicationIdSuffix = ".play"
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("play")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    sourceSets.getByName("main") {
        kotlin.directories += rootProject.file("shared-preview/src/main/java").path
        jniLibs.srcDir(portraitRuntimeDir.get().dir("jniLibs").asFile)
        assets.srcDir(portraitRuntimeDir.get().dir("assets").asFile)
    }
    sourceSets.getByName("debug") {
        if (evaluatePortraitGpu) {
            jniLibs.srcDir(portraitGpuRuntimeDir.get().dir("jniLibs").asFile)
            assets.srcDir(portraitGpuRuntimeDir.get().dir("assets").asFile)
        }
    }
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }
    defaultConfig.externalNativeBuild.ndkBuild {
        arguments += listOf("APP_STL=c++_static", "APP_OPTIM=release")
    }

    androidResources {
        localeFilters += listOf("en")
    }
}

dependencies {
    add(portraitRuntimeInputs.name, "com.google.ai.edge.litert:litert:2.3.0@aar")
    add(portraitRuntimeInputs.name, "com.google.ai.edge.litert:litert-api:2.3.0@aar")
    add(portraitGpuInputs.name, "com.google.ai.edge.litert:litert-gpu:2.3.0@aar")
    debugImplementation(portraitCompiledApiJar)
    implementation(portraitRuntimeJar)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)

    implementation(libs.bundles.camerax)

    implementation(libs.zxing.core)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
}
