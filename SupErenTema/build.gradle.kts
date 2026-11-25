version = 2

cloudstream {
    description = "SupEren v2.0 - Yüzen Alt Menü (boyut/opaklık ayarlı) ve 6 Vurgu Rengi (TV'de yalnızca kumanda odak rengi)."
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
