# GokBey-Develop — Birlikteİzleyelim (CloudStream Watch Party)

Sevdiklerinizle CloudStream üzerinden aynı anda, senkronize şekilde film ve dizi izlemenizi sağlayan eklenti.

## Kurulum

CloudStream uygulamasında **Ayarlar > Eklentiler > Depo Ekle** bölümüne aşağıdaki adresi yapıştırın:

```text
https://raw.githubusercontent.com/gokberkeren46-creator/GokBey-Develop/master/repo.json
```

Tek tıkla kurulum bağlantısı:
`cloudstreamrepo://raw.githubusercontent.com/gokberkeren46-creator/GokBey-Develop/master/repo.json`

## Özellikler

- **6 Haneli Oda Sistemi:** Bir kişi oda açar, diğerleri 6 haneli kodu girerek katılır.
- **Sormadan Otomatik İçerik Açma:** Biri bir film veya bölüm başlattığında odadaki diğer cihazlar aynı kaynaktan otomatik oynatmaya geçer.
- **Herkes Kontrol Edebilir:** Odadaki herkes oynatabilir, duraklatabilir veya ileri/geri sarabilir (son işlem geçerlidir).
- **Kararlı Senkronizasyon:** Kopma algılamalı SSE bağlantısı (`45s` zaman aşımı), tamponlama filtresi ve 2 saniye üstü sapmalarda otomatik eşitleme.
- **Canlı Emoji Tepkileri:** İzlerken sağ üstteki `😊` butonundan (tablet veya TV kumandasıyla) ekranda süzülen emojiler gönderebilirsiniz.

## Kullanım

1. CloudStream **Ayarlar > Eklentiler > Birlikteİzleyelim** yanındaki **Ayarlar (dişli)** butonuna basın.
2. Görünecek adınızı yazın ve **Oda Oluştur** veya **Odaya Katıl** seçeneğini kullanın.
3. Normal şekilde bir içerik açın; odadaki diğer cihazlar otomatik olarak aynı içeriğe geçer ve oynatma kontrolleri senkronize edilir.

## Firebase Realtime Database Kuralları

Firebase Console > **Realtime Database > Rules** sekmesinde aşağıdaki kuralları kullanabilirsiniz:

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
