import java.security.MessageDigest
plugins { alias(libs.plugins.android.application) }
val runtime=project(":app").layout.buildDirectory.dir("generated/portrait-runtime")
val gpuRuntime=project(":app").layout.buildDirectory.dir("generated/portrait-gpu-runtime")
val prepare=tasks.register<Sync>("preparePreviewAssets") {
    dependsOn(":app:preparePortraitRuntime",":app:preparePortraitGpuRuntime",":app:verifyPortraitModels")
    from(project(":app").file("src/main/assets")) {include("portrait_subject_v1.*")}
    into(layout.buildDirectory.dir("generated/preview-assets"))
}
android {
    namespace="app.grapheneos.camera.processor"
    compileSdk=37;buildToolsVersion="37.0.0";ndkVersion="29.0.14206865"
    defaultConfig {
        applicationId="app.grapheneos.camera.processor";minSdk=33;targetSdk=37
        versionCode=1;versionName="1"
        externalNativeBuild.ndkBuild {arguments+=listOf("APP_STL=c++_static","APP_OPTIM=release")}
    }
    buildTypes {
        getByName("debug") {ndk.abiFilters+=listOf("arm64-v8a","armeabi-v7a","x86_64");applicationIdSuffix=".dev";isDebuggable=false;isMinifyEnabled=true;proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")}
        getByName("release") {ndk.abiFilters+="arm64-v8a";isMinifyEnabled=true;proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")}
    }
    compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
    sourceSets.getByName("main") {
        kotlin.directories += rootProject.file("shared-preview/src/main/java").path
        jniLibs.srcDirs(runtime.get().dir("jniLibs").asFile,gpuRuntime.get().dir("jniLibs").asFile)
        assets.srcDirs(runtime.get().dir("assets").asFile,gpuRuntime.get().dir("assets").asFile,layout.buildDirectory.dir("generated/preview-assets").get().asFile)
    }
    externalNativeBuild.ndkBuild {path=file("src/main/cpp/Android.mk")}
    androidResources.noCompress+="tflite"
}
java {toolchain {languageVersion.set(JavaLanguageVersion.of(17))}}
tasks.named("preBuild").configure {dependsOn(prepare)}
dependencies {
    implementation(files(runtime.map {it.file("portrait-compiled-api.jar")}).builtBy(":app:preparePortraitRuntime"))
    implementation(files(runtime.map {it.file("portrait-cpu.jar")}).builtBy(":app:preparePortraitRuntime"))
}

val verifyPreviewDepth=tasks.register("verifyPreviewDepth") {
    val model=file("src/main/assets/portrait_preview_depth_v2_small.tflite")
    inputs.file(model)
    doLast {
        check(model.length()==48_740_176L)
        val hash=MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") {"%02x".format(it.toInt() and 255)}
        check(hash=="bde1e29c8fe0be113901d87ad9fa9070a3ef5d4f1513d9cc65afc1248853a704")
    }
}
tasks.named("preBuild").configure {dependsOn(verifyPreviewDepth)}
