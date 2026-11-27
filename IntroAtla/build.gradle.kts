version = 2

cloudstream {
    description = "IntroAtla - Dizi ve animelerde sol alt köşede Netflix tarzı 'İntro Atla' butonu gösterir (IntroDB/AniSkip API + ayarlanabilir +85sn yedek). Kaynak kodlar ücretsiz olarak paylaşılmıştır, özel destek isterseniz ücretli verilecektir. insta=gokberkerenn. Ücretsiz danışmanlık için lütfen rahatsız etmeyin ‼️"
    authors = listOf("gokberkeren46-creator")
    status = 1
    tvTypes = listOf("All")
    requiresResources = false
    language = "tr"
    iconUrl = "https://raw.githubusercontent.com/gokberkeren46-creator/SupEren.Dev/master/icon.png"
}

dependencies {
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    compileOnly("androidx.fragment:fragment-ktx:1.8.5")
    compileOnly("androidx.appcompat:appcompat:1.7.0")
}

android {
    namespace = "com.gokberkeren.introatla"
}
