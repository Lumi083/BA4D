plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val copyLauncherIcon = tasks.register<Copy>("copyLauncherIcon") {
    from("${rootDir}/icon.png")
    into(layout.buildDirectory.dir("generated/res/icon/mipmap-xxxhdpi"))
    rename { "ic_launcher.png" }
}

val syncVersionToAssets = tasks.register("syncVersionToAssets") {
    doLast {
        val versionFile = file("src/main/assets/version.txt")
        versionFile.parentFile.mkdirs()
        versionFile.writeText(android.defaultConfig.versionName ?: "unknown")
    }
}

android {
    namespace = "com.miradesktop.ba4d"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miradesktop.ba4d"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "${rootDir}/src/native")
            res.srcDir(layout.buildDirectory.dir("generated/res/icon"))
        }
    }
}

tasks.named("preBuild") {
    dependsOn(copyLauncherIcon, syncVersionToAssets)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
