version = 3

cloudstream {
    description = "SupEren v2.0 - Tablet ve TV uyumlu Yüzen Alt Menü (boyut/koyuluk ayarlı, TV'de kumandayla otomatik açılır) ve 6 Vurgu Rengi."
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
