import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Секреты подписи релиза живут в local.properties (вне git). Их отсутствие не ломает сборку: release
// тогда собирается неподписанным, debug не затронут — репозиторий собирается у любого без секретов.
val secrets = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? = secrets.getProperty(name) ?: System.getenv(name)

android {
    namespace = "ru.papasheets"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.papasheets"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val storePassword = secret("RELEASE_STORE_PASSWORD")
        val keyPassword = secret("RELEASE_KEY_PASSWORD")
        val keyAliasName = secret("RELEASE_KEY_ALIAS")
        val keystoreFile = rootProject.file("keystore/papasheets-release.keystore")
        if (storePassword != null && keyPassword != null && keyAliasName != null && keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                this.storePassword = storePassword
                this.keyAlias = keyAliasName
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // null, если секретов подписи нет → release собирается неподписанным (debug не затронут).
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // MigrationTestHelper читает экспортированные схемы с устройства, а не из исходников —
    // без этого каталога в assets тест миграции не найдёт 1.json и упадёт на старте.
    sourceSets.getByName("androidTest") {
        assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(project(":matrixgrid"))
    implementation(project(":exportkit"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)

    // Миграцию нельзя проверить на JVM: MigrationTestHelper прогоняет её на настоящем SQLite
    // и сверяет результат с экспортированной схемой — только так ловится расхождение по identityHash.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
