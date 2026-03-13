plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yuliwen.filetran.airgap"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DOpenCV_DIR=${projectDir}/third_party/OpenCV-android-sdk/sdk/native"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("third_party/OpenCV-android-sdk/sdk/native/libs")
            jniLibs.srcDir("$buildDir/generated/jniLibs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

val copyCxxShared by tasks.registering(org.gradle.api.tasks.Copy::class) {
    val ndkRoot = File(
        System.getenv("ANDROID_NDK_HOME")
            ?: "${System.getenv("LOCALAPPDATA")}\\Android\\Sdk\\ndk\\27.0.12077973"
    )
    val libcRoot = File(ndkRoot, "toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib")
    into("$buildDir/generated/jniLibs")

    from(File(libcRoot, "aarch64-linux-android/libc++_shared.so")) { into("arm64-v8a") }
    from(File(libcRoot, "arm-linux-androideabi/libc++_shared.so")) { into("armeabi-v7a") }
    from(File(libcRoot, "x86_64-linux-android/libc++_shared.so")) { into("x86_64") }
}

tasks.named("preBuild").configure {
    dependsOn(copyCxxShared)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
}
