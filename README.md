# GyroLock Android — prototipe stabilisasi gyro

## Fitur
- Kamera dalam aplikasi (CameraX).
- Merekam timestamp sensor gyroscope bersamaan dengan video.
- Setelah rekaman berhenti, menyimpan log CSV dan otomatis menjalankan FFmpeg.
- Proses koreksi prototype memakai gyro Z untuk koreksi rotasi/roll, smoothing sudut, dan crop untuk menyembunyikan tepi hitam.
- GitHub Actions membangun debug APK dan mengunggahnya sebagai artifact.

## Batasan penting
Ini prototipe, bukan stabilisasi setara Gyroflow. Implementasi saat ini hanya memakai satu sumbu gyro (Z) dan tidak menyelesaikan pitch/yaw, translasi, rolling shutter, atau kalibrasi lensa. Orientasi sensor HP berbeda-beda, jadi hasil perlu diuji. Jangan klaim semua video akan lebih stabil. Jika ekspresi filter gagal pada video panjang, kurangi durasi atau optimalkan pemilihan sampel.
Hasil dan video mentah disimpan pada direktori internal aplikasi `Android/data/com.ridiitols.gyrolock/files/GyroLock/` (akses bisa dibatasi oleh Android).

## Build APK
1. Buat repo GitHub lalu upload seluruh isi folder ini (termasuk `.github/workflows/build.yml`).
2. Buka tab **Actions** dan jalankan workflow **Build GyroLock APK**, atau push ke branch `main`/`master`.
3. Setelah selesai, buka run workflow → **Artifacts** → unduh `GyroLock-debug-apk`.
4. Ekstrak ZIP artifact dan pasang `app-debug.apk` di Android.

## Catatan build
Workflow menggunakan Gradle 8.9 dan Java 17. Dependency FFmpegKit menggunakan maintained fork `dev.ffmpegkit-maintained:ffmpeg-kit-full:6.0.3`; jika repository dependency berubah, build log GitHub Actions akan menunjukkan error resolusi.


## Perubahan revisi V3
- Tombol berhenti sekarang menyimpan video asli terlebih dahulu; stabilisasi dimulai dengan menekan tombol `STABILKAN VIDEO` setelah rekaman selesai.
- Ekspresi koreksi gyro dibatasi menjadi maksimal 24 titik dan dibangun dengan nested conditional yang seimbang.
- Kegagalan FFmpeg ditampilkan di status aplikasi; video asli tidak dihapus.
- Ini masih prototipe stabilisasi berbasis gyro Z, bukan stabilisasi 3-sumbu penuh. Uji di perangkat tetap diperlukan.
