# Gofile ZIP (Android)

Gofile linkindeki (alt klasörler dahil) tüm dosyaları indirip tek bir ZIP arşivi
yapan basit bir Android uygulaması.

## Özellikler

- Birden fazla link: her satıra bir link yazın, her link ayrı bir ZIP olur
- Şifreli klasör desteği
- "Listele" ile indirmeden önce dosya sayısı ve toplam boyutu görme
- Ön plan servisi: uygulama arka plana alınsa da indirme sürer, bildirimden iptal edilebilir
- Hatalı dosyada 3 deneme; indirilemeyen dosya atlanır, sonuçta bildirilir
- Tarayıcıdan "Paylaş" ile ya da `gofile.io/d/...` linkine dokunarak açılabilir
- ZIP'ler `İndirilenler/Gofile/` klasörüne kaydedilir

Gereksinim: Android 10 (API 29) veya üstü.

## APK'yı bilgisayarsız üretme (GitHub Actions)

1. github.com'da yeni bir depo açın.
2. Bu klasörün içeriğini (`.github` klasörü dahil) depoya yükleyin.
3. Depoda **Actions → Build APK → Run workflow** deyin.
4. İş bittiğinde sayfanın altındaki **GofileZip-debug-apk** artifact'ını indirin,
   içinden `app-debug.apk` çıkar ve telefona kurun ("bilinmeyen kaynaklardan kuruluma izin ver" gerekir).

## Bilgisayarda derleme

Klasörü Android Studio ile açıp **Run** deyin, ya da Gradle kuruluysa:

```
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Bilinmesi gerekenler

- **Test edilmedi.** Bu proje yazıldığı ortamda derleme ve ağ erişimi yoktu; ne derlendi
  ne de gerçek bir Gofile linkiyle denendi. İlk derlemede küçük hatalar çıkabilir.
- **Kırılgan nokta:** Gofile, istekleri `X-Website-Token` başlığıyla doğruluyor. Bu değerin
  formülü resmi API dokümanında yok; sitenin kendi arayüzünden çıkarılmış bir yöntem
  (`GofileClient.websiteToken()`). Gofile bunu değiştirirse listeleme "yetkisiz" benzeri bir
  hatayla başarısız olur; düzeltme tek fonksiyonda yapılır.
- Çok büyük arşivlerde (4 GB üzeri veya 65 bin dosyadan fazla) Android'in ZIP yazıcısının
  sınırlarına takılabilirsiniz; bu denenmedi.
- Sadece indirme hakkınız olan içerikleri indirin.
