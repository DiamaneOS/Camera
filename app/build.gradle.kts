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
