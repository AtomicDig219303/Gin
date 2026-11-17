plugins {
    id("com.android.application")
}

android {
    namespace = "com.custom.gin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.custom.gin"
        minSdk = 19
        targetSdk = 19
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
        checkReleaseBuilds = false
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (variant.name == "release") {
                output.outputFileName = "Gin-Glass.apk"
            } else {
                output.outputFileName = "Gin-Glass-${variant.name}.apk"
            }
        }
    }
}

dependencies {
    compileOnly(files("libs/gdk.jar"))
}
