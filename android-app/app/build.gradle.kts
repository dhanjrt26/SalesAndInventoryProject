plugins {
    id("com.android.application")
}

val localProperties = java.util.Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val supabaseUrl = providers.gradleProperty("supabaseUrl")
    .orElse(localProperties.getProperty("supabaseUrl", ""))
val supabaseAnonKey = providers.gradleProperty("supabaseAnonKey")
    .orElse(localProperties.getProperty("supabaseAnonKey", ""))
val apiBaseUrl = providers.gradleProperty("apiBaseUrl")
    .orElse(localProperties.getProperty("apiBaseUrl", "https://your-api.example.com"))

android {
    namespace = "com.example.salesinventory.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.salesinventory.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.get().escapeForJava()}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseAnonKey.get().escapeForJava()}\"")
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.get().escapeForJava()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

fun String.escapeForJava(): String = replace("\\", "\\\\").replace("\"", "\\\"")
