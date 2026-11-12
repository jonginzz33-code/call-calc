plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// 버전 이름은 README 맨 끝 '## v…' 에서 읽고, 버전 번호는 빌드 번호로 자동 증가
val readmeVersion: String = run {
    val f = rootProject.file("README.md")
    if (!f.exists()) "0.0" else
        Regex("^## v([0-9][0-9.]*)", RegexOption.MULTILINE).findAll(f.readText())
            .lastOrNull()?.groupValues?.get(1) ?: "0.0"
}
val buildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 1

android {
    namespace = "com.jongin.callcalc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jongin.callcalc"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = readmeVersion
    }

    buildFeatures { buildConfig = true }

    // 저장소에 넣어 둔 고정 디버그 키로 서명: 새 버전을 기존 앱 위에 덮어 설치할 수 있게
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    testImplementation("org.robolectric:robolectric:4.14.1") { exclude(group = "org.robolectric", module = "nativeruntime-dist-compat") }
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    // 한국어 글자 인식. 모델이 앱 안에 들어 있는(bundled) 버전이라 인터넷이 필요 없음
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // 서버 (Firebase · 계산기 전용 프로젝트): 구글 로그인, 2인 1조 팀 알림, 수락한 콜 기록
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
