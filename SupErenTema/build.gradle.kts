version = 1

cloudstream {
    description = "SupErenv2.0 Tema - Tablet ve TV için Yüzen Cam Alt Menü (Floating Dock) ve 6 Vurgu Rengi."
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
