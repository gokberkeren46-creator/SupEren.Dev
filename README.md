# SupEren.Dev — Birlikteİzleyelim (CloudStream Watch Party)

Sevdiklerinizle CloudStream üzerinden aynı anda, senkronize şekilde film ve dizi izlemenizi sağlayan **açık kaynak (MIT)** eklenti.

## Kurulum

CloudStream uygulamasında **Ayarlar > Eklentiler > Depo Ekle** bölümüne aşağıdaki adresi yapıştırın:

```text
https://raw.githubusercontent.com/gokberkeren46-creator/SupEren.Dev/master/repo.json
```

Tek tıkla kurulum bağlantısı:
`cloudstreamrepo://raw.githubusercontent.com/gokberkeren46-creator/SupEren.Dev/master/repo.json`

## Özellikler

- **Kendi Firebase Sunucunuzla Çalışır:** Varsayılan ortak sunucu kullanılmaz; her kullanıcı ücretsiz kendi Firebase Realtime Database adresini bir kez girip kaydeder.
- **6 Haneli Oda Sistemi:** Bir kişi oda açar, diğerleri 6 haneli kodu girerek katılır.
- **Otomatik İçerik Açma:** Biri bir film veya bölüm başlattığında odadaki diğer cihazlar aynı kaynaktan otomatik oynatmaya geçer.
- **Kesintisiz Yumuşak Senkron:** 0.8–15 sn arası farklarda videoyu kesmeden `±%5` mikro-hız ayarıyla eşitler; oynatma hızı ve kaynak değişikliklerini paylaşır.
- **Canlı Emoji Tepkileri:** İzlerken sağ üstteki `😊` butonundan 8 hızlı emoji (`😂 ❤️ 🔥 💀 😱 😭 🤬 🤯`) gönderebilirsiniz.

## Kullanım

1. Firebase Console'da ücretsiz bir **Realtime Database** oluşturun ve kuralları aşağıdakine göre ayarlayın.
2. CloudStream **Ayarlar > Eklentiler > Birlikteİzleyelim** yanındaki **Ayarlar (dişli)** butonuna basın.
3. **Sunucu / Kısa Kod** kutusuna kendi Firebase Realtime Database URL'nizi (`https://...firebasedatabase.app`) bir kez girin.
4. **Yeni Oda Oluştur** veya **Odaya Katıl** ile izlemeye başlayın.

## Firebase Realtime Database Kuralları

```json
{
  "rules": {
    "rooms": {
      "$code": {
        ".read": "$code.matches(/^[0-9]{6}$/)",
        ".write": "$code.matches(/^[0-9]{6}$/)"
      }
    }
  }
}
```

## Fork ve Kendi Derlemeniz

Bu depo MIT lisansıyla açık kaynak olarak paylaşılmıştır. Kendi bilgisayarınızda derlemek için:

```bash
./gradlew BirlikteIzleyelim:make makePluginsJson
```
