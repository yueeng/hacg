import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("kotlin-kapt")
}

android {
    compileSdkVersion(30)
    buildToolsVersion = "30.0.3"

    defaultConfig {
        applicationId = "io.github.yueeng.hacg"
        minSdkVersion(21)
        targetSdkVersion(30)
        versionCode = 39
        versionName = "1.5.3"
        resConfigs("zh-rCN")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            val config = gradleLocalProperties(rootDir)
            storeFile = file(config.getProperty("storeFile"))
            storePassword = config.getProperty("storePassword")
            keyAlias = config.getProperty("keyAlias")
            keyPassword = config.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    applicationVariants.all {
        if (name != "release") return@all
        outputs.all {
            project.tasks.getByName("assembleRelease").doLast {
                val folder = File(rootDir, "release").also { it.mkdirs() }
                outputFile.parentFile.listFiles()?.forEach { it.copyTo(File(folder, it.name), true) }
            }
        }
    }
}

dependencies {
    val glideVersion = "4.12.0"
    val okhttpVersion = "4.9.1"
    val kotlinxCoroutinesVersion = "1.5.0"
    implementation("androidx.appcompat:appcompat:1.3.0")
    implementation("com.google.android.material:material:1.3.0")
    implementation("androidx.preference:preference-ktx:1.1.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.slidingpanelayout:slidingpanelayout:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.0.4")
    implementation("androidx.fragment:fragment-ktx:1.3.4")
    implementation("androidx.paging:paging-runtime-ktx:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinxCoroutinesVersion")
    implementation("com.github.clans:fab:1.6.4")
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$okhttpVersion")
    implementation("com.github.bumptech.glide:glide:$glideVersion")
    implementation("com.github.bumptech.glide:okhttp3-integration:$glideVersion")
    kapt("com.github.bumptech.glide:compiler:$glideVersion")
    implementation("org.jsoup:jsoup:1.13.1")
    implementation("gun0912.ted:tedpermission:2.2.3")
    implementation("com.google.code.gson:gson:2.8.7")
}