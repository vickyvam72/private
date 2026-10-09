# Lumi Signal 1.6.0 — Strategy-Aware Top 5

## Perubahan utama

- Pengaturan minimum liquidity dan risk management dihapus dari UI dan runtime screening.
- Home mewajibkan pengguna memilih satu dari 10 strategi. Setiap strategi memiliki Top 5, score, alasan, indikator, entry, TP, dan SL sendiri.
- Identitas hasil adalah kombinasi `batch + strategy + ticker`; ticker yang sama dapat tersimpan pada beberapa strategi tanpa mencampur analisisnya.
- Top 5 bersifat ketat: hanya kandidat yang mencapai ambang 8/10 atau 9/10 sesuai spesifikasi. Jika kandidat valid kurang dari lima, aplikasi menampilkan jumlah aktual.
- Entry/TP/SL dihitung otomatis dari trigger, support/resistance, ATR, dan struktur strategi. Tidak ada parameter risiko yang dapat diubah pengguna.
- Stockbit wajib untuk screening dan aktivasi audit. Telegram opsional.
- Audit latar belakang memakai foreground service, berjalan setiap satu menit pada sesi BEI, dan menampilkan notifikasi lokal saat entry, TP, atau SL tersentuh.

## Batas integritas data

Source yang tersedia menyediakan OHLCV Yahoo, estimasi nilai transaksi `close × volume`, dan broker summary agregat Stockbit. Source belum menyediakan data historis lengkap untuk frekuensi transaksi, free-float shares, UMA, FCA/Papan Pemantauan Khusus, bid-offer spread, order book, crossing, dan seluruh corporate action.

Nilai yang tidak tersedia tidak direkayasa. Setiap kriteria tersebut disimpan sebagai `DATA_UNAVAILABLE` dan ditampilkan di detail. Akibatnya, khususnya Frequency Creep dapat menghasilkan nol kandidat sampai feed frekuensi historis tersedia. Ini lebih ketat daripada mengisi Top 5 dengan proxy yang tidak dapat diverifikasi.

## Audit latar belakang

- Foreground service: `AuditForegroundService`.
- Interval: satu menit selama sesi Senin–Kamis 09:00–12:00 dan 13:30–15:50 WIB; Jumat 09:00–11:30 dan 14:00–15:50 WIB.
- Kalender libur bursa belum tersedia; pada hari libur yang jatuh pada hari kerja service dapat tetap mencoba mengambil harga, tetapi tidak mengubah status jika data intraday tidak tersedia.
- Audit hanya memantau Top 5 dari strategi yang dipilih ketika sakelar diaktifkan.
- Android dapat menghentikan service bila aplikasi dibatasi penghemat baterai; UI menampilkan peringatan dan membuka pengaturan optimasi baterai.

## Catatan build lingkungan ini

Project menargetkan Android SDK 35, JDK 17, Gradle 8.9, dan versi aplikasi `1.6.0` (`versionCode 10600`). Verifikasi build memerlukan distribusi Gradle 8.9. Bila wrapper tidak dapat mengakses `services.gradle.org`, build harus dijalankan di Android Studio atau lingkungan yang sudah memiliki distribusi tersebut.
