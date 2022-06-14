import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("kotlin-kapt")
}

android {
    compileSdk = 31
    buildToolsVersion = "31.0.0"

    defaultConfig {
        applicationId = "io.github.yueeng.hacg"
        minSdk = 21
        targetSdk = 31
        versionCode = 40
        versionName = "1.5.4"
        resourceConfigurations.add("zh-rCN")
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
    val glideVersion = "4.13.2"
    val okhttpVersion = "4.9.3"
    val kotlinxCoroutinesVersion = "1.6.1-native-mt"
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("com.google.android.material:material:1.6.1")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.slidingpanelayout:slidingpanelayout:1.2.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.4.1")
    implementation("androidx.paging:paging-runtime-ktx:3.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinxCoroutinesVersion")
    implementation("com.github.clans:fab:1.6.4")
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$okhttpVersion")
    implementation("com.github.bumptech.glide:glide:$glideVersion")
    implementation("com.github.bumptech.glide:okhttp3-integration:$glideVersion")
    kapt("com.github.bumptech.glide:compiler:$glideVersion")
    implementation("org.jsoup:jsoup:1.15.1")
    implementation("com.google.code.gson:gson:2.9.0")
}