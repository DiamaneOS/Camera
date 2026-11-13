import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val useKeystoreProperties = keystorePropertiesFile.canRead()
val keystoreProperties = Properties()
if (useKeystoreProperties) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
}

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
    ndkVersion = "30.0.16248370"
    androidResources.noCompress += "tflite"

    namespace = "app.grapheneos.camera"

    defaultConfig {
        applicationId = "app.grapheneos.camera"
        // DiamaneOS: the Camera's own preview processor app (module processor).
        manifestPlaceholders["portraitProcessorPackage"]="de.diamaneos.camera.processor"
        buildConfigField("String","PORTRAIT_PROCESSOR_PACKAGE","\"de.diamaneos.camera.processor\"")
        minSdk = 29
        targetSdk = 37
        versionCode = 94
        versionName = versionCode.toString()

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
