import java.util.Properties

version = 2

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val firebaseUrl: String = (
    System.getenv("FIREBASE_URL")?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty("firebase.url")?.takeIf { it.isNotBlank() }
        ?: "https://cloudstream-4616-default-rtdb.europe-west1.firebasedatabase.app/"
).trim().trimEnd('/')

cloudstream {
    description = "Birlikteİzleyelim - Sevdiklerinizle senkronize izleyin"
    authors = listOf("gokberkeren46-creator")
    status = 1
    tvTypes = listOf("All")
    requiresResources = false
    language = "tr"
    iconUrl = "https://raw.githubusercontent.com/gokberkeren46-creator/GokBey-Develop/master/icon.png"
}

dependencies {
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    compileOnly("androidx.fragment:fragment-ktx:1.8.5")
    compileOnly("androidx.appcompat:appcompat:1.7.0")
}

android {
    namespace = "com.gokberkeren.birlikteizle"

    defaultConfig {
        buildConfigField("String", "FIREBASE_URL", "\"$firebaseUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }
}
