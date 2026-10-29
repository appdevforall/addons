plugins {
	id("com.android.library")
}

kotlin {
	compilerOptions {
		jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
	}
}

android {
	namespace = "android.llama.cpp"
	compileSdk = 36
	// Pinned so a rebuild doesn't silently swap clang/libomp when AGP's default NDK moves.
	ndkVersion = "27.0.12077973"

	defaultConfig {
		minSdk = 33
		consumerProguardFiles("proguard-rules.pro")
		ndk {
			abiFilters += listOf("arm64-v8a")
		}
		externalNativeBuild {
			cmake {
				arguments += "-DLLAMA_CURL=OFF"
				arguments += "-DLLAMA_BUILD_COMMON=ON"
				arguments += "-DGGML_LLAMAFILE=OFF"
				// The CPU backend as one library per instruction-set level, picked at runtime by what the
				// phone supports (ADFA-6757). Left alone, the cross-compile falls back to basic ARMv8 and
				// the shipped library holds no dot-product instructions at all, yet the ARMv8.0 variant
				// keeps old Cortex-A53 phones working. Hard-coding armv8.2-a+dotprod would not.
				arguments += "-DBUILD_SHARED_LIBS=ON"
				arguments += "-DGGML_BACKEND_DL=ON"
				arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
				arguments += "-DCMAKE_BUILD_TYPE=Release"
				// 16 KB page alignment, required on newer arm64 devices; PrebuiltAarAbiTest asserts it
				// on the committed AAR, since dropping this still builds and still loads on 4 KB.
				arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
				cppFlags += listOf()
				arguments += listOf()

				cppFlags("")
			}
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	externalNativeBuild {
		cmake {
			path("src/main/cpp/CMakeLists.txt")
			version = "3.22.1"
		}
	}

	packaging {
		resources {
			excludes += "/META-INF/{AL2.0,LGPL2.1}"
		}
	}
}

dependencies {
	implementation(project(":llama-api"))
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.appcompat)
	implementation(libs.slf4j.api)
}
