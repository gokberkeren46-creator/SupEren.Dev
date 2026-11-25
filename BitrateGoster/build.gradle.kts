version = 1

cloudstream {
    description = "BitrateGoster - Oynatıcıyı duraklattığınızda sol üst köşede videonun bitrate değerini gösterir. Kaynak kodlar ücretsiz olarak paylaşılmıştır, özel destek isterseniz ücretli verilecektir."
    authors = listOf("gokberkeren46-creator")
    status = 1
    tvTypes = listOf("All")
    requiresResources = false
    language = "tr"
    iconUrl = "https://raw.githubusercontent.com/gokberkeren46-creator/SupEren.Dev/master/icon_bitrate.png"
}

dependencies {
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    compileOnly("androidx.fragment:fragment-ktx:1.8.5")
    compileOnly("androidx.appcompat:appcompat:1.7.0")
}

android {
    namespace = "com.gokberkeren.bitrategoster"
}
