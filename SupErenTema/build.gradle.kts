version = 6

cloudstream {
    description = "SupEren v2.0 - Tablet ve TV uyumlu Yüzen Alt Menü (boyut/koyuluk ayarlı, TV'de en solda ◀ veya Geri ile açılır) ve 12 Vurgu Rengi. Kaynak kodlar ücretsiz olarak paylaşılmıştır, özel destek isterseniz ücretli verilecektir. insta=gokberkerenn. Ücretsiz danışmanlık için lütfen rahatsız etmeyin ‼️"
    authors = listOf("gokberkeren46-creator")
    status = 1
    tvTypes = listOf("All")
    requiresResources = false
    language = "tr"
    iconUrl = "https://raw.githubusercontent.com/gokberkeren46-creator/SupEren.Dev/master/icon_tema.png"
}

dependencies {
    compileOnly("androidx.fragment:fragment-ktx:1.8.5")
    compileOnly("androidx.appcompat:appcompat:1.7.0")
}

android {
    namespace = "com.gokberkeren.superentema"
}
