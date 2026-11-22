import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
}

android {
    namespace = "org.yasvara.pentas"
    compileSdk = 36

    defaultConfig {
        applicationId =  "org.yasvara.pentas"
        minSdk = 36
        targetSdk = 36
        versionCode = 100
        versionName = "0.2"
    }
    signingConfigs {
        create("platform") {
            storeFile = file("platform.jks")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("platform")
        }
        getByName("release") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("platform")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        named("main") {
            aidl.srcDirs("src/main/java")
        }
    }

    buildFeatures {
        aidl = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

kapt {
    correctErrorTypes = true
}

// Hidden platform APIs and Soong-only libs, taken from the ROM's out/ tree
// (override with -Pyasvara.out=/path/to/out). framework.jar must precede
// android.jar so its hidden members resolve.
val romOut = file(providers.gradleProperty("yasvara.out").getOrElse("${rootDir}/../../../out"))
val romJars = listOf(
    "frameworks/base/framework/android_common/turbine-combined/framework.jar",
    "frameworks/base/packages/SettingsLib/DisplayUtils/SettingsLibDisplayUtils/android_common/turbine/SettingsLibDisplayUtils.jar",
    "packages/apps/Rekam/rekam-controls/android_common/kotlin_headers/rekam-controls.jar",
    "packages/apps/Rekam/rekam-client/android_common/kotlin_headers/rekam-client.jar",
    "packages/apps/Rekam/rekam-client/android_common/javac/rekam-client.jar",
    "vendor/yasvara/pantau/aidl/yasvara.pantau-java/android_common/turbine/yasvara.pantau-java.jar",
).map { romOut.resolve("soong/.intermediates/$it") }.filter { it.exists() }

dependencies {
    compileOnly(fileTree(mapOf("dir" to "../system_libs", "include" to listOf("*.jar"))))
    compileOnly(files(romJars))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.material3:material3:1.5.0-alpha16")
    implementation("androidx.compose.ui:ui:1.10.0")
    implementation("androidx.compose.ui:ui-graphics:1.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.google.dagger:hilt-android:2.60.1")
    kapt("com.google.dagger:hilt-compiler:2.60.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    doFirst {
        val prev = libraries.from.toList()
        libraries.setFrom(romJars, prev)
    }
}

tasks.withType<JavaCompile>().configureEach {
    doFirst {
        classpath = files(romJars) + classpath
    }
}
