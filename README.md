# Birlikte İzle (CloudStream Watch Party)

Sevdiklerinizle CloudStream üzerinden aynı anda, senkronize şekilde film ve dizi izlemenizi sağlayan eklenti.

## Kurulum

CloudStream uygulamasında **Ayarlar > Eklentiler > Depo Ekle** bölümüne aşağıdaki adresi veya kısa kodu yapıştırın:

```text
https://raw.githubusercontent.com/gokberkeren46-creator/birlikte-izle/master/repo.json
```

Tek tıkla kurulum bağlantısı:
`cloudstreamrepo://raw.githubusercontent.com/gokberkeren46-creator/birlikte-izle/master/repo.json`

## Özellikler

- **6 Haneli Oda Sistemi:** Bir kişi oda açar, diğerleri 6 haneli kodu girerek katılır.
- **Herkes Kontrol Edebilir:** Odadaki herkes oynatabilir, duraklatabilir veya ileri/geri sarabilir (son işlem geçerlidir).
- **Otomatik Sapma Düzeltme:** 2 saniyeden büyük zaman farklarında oynatıcı otomatik eşitlenir.
- **İçerik Paylaşımı:** Biri yeni bir içerik açtığında diğer üyelere **"Açmak ister misin?"** bildirimi gider.

## Kullanım

1. CloudStream **Ayarlar > Eklentiler > Birlikte İzle** simgesinin yanındaki **Ayarlar (dişli)** butonuna basın.
2. Görünecek adınızı yazın ve **Oda Oluştur** veya **Odaya Katıl** seçeneğini kullanın.
3. Normal şekilde bir içerik açın; oynatma kontrolleriniz oda üyeleriyle anlık paylaşılır.

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

## GitHub Actions Kurulumu

1. GitHub'da `gokberkeren46-creator/birlikte-izle` adlı yeni bir repo oluşturun.
2. Repo ayarlarından **Settings > Secrets and variables > Actions** altına `FIREBASE_URL` secret'ını ekleyin (örn. `https://cloudstream-4616-default-rtdb.europe-west1.firebasedatabase.app/`).
3. **Settings > Actions > General > Workflow permissions** altında **Read and write permissions** seçeneğini işaretleyin.
4. Derlenen `.cs3` dosyalarının yayınlanacağı boş bir `builds` dalı oluşturun:
   ```bash
   git checkout --orphan builds
   git rm -rf .
   git commit --allow-empty -m "Init builds"
   git push -u origin builds
   git checkout master
   ```
5. `master` dalını push edin; GitHub Actions otomatik olarak `BirlikteIzle.cs3` ve `plugins.json` dosyalarını derleyip `builds` dalına aktaracaktır.
