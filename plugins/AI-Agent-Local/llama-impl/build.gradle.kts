plugins {
	id("com.android.library")
	id("org.jetbrains.kotlin.android")
}

kotlin {
	compilerOptions {
		jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
	}
}

android {
	namespace = "android.llama.cpp"
	compileSdk = 36

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
