# Lumi Signal 1.7.0 — All-Strategy Audit

## Perubahan utama

- Audit latar belakang memantau seluruh kandidat Top 5 dari sepuluh strategi, bukan hanya strategi yang sedang dipilih di Home.
- Setiap hasil screening otomatis dibuat menjadi snapshot signal lokal. Dengan demikian, History Semua Strategi tetap lengkap meski signal tidak dikirim manual ke Telegram.
- Perubahan status entry, TP, SL, expired, atau ambiguous dikirim ke Telegram bila credential Telegram tersedia. Gangguan Telegram tidak menghentikan audit lokal.
- Harga entry berubah menjadi harga eksekusi aktual berwarna hijau setelah area entry tersentuh. TP menjadi hijau ketika tercapai; SL menjadi merah ketika tersentuh.
- History memiliki dua sumber: Telegram dan Semua Strategi. Ticker yang masuk beberapa strategi pada tanggal referensi yang sama ditampilkan satu kali, dengan semua setup strateginya tetap tersedia di dalam kartu.
- Screener satu ticker menghitung dan menampilkan skor seluruh sepuluh strategi. Pengguna dapat memilih kartu skor untuk membuka alasan, indikator, trade plan, dan chart khusus strategi tersebut.
- Dropdown strategi memakai deskripsi singkat yang menjelaskan karakter setup, bukan label fase generik.

## Integritas logika

Penggabungan ticker hanya diterapkan pada tampilan History. Database tetap menyimpan signal terpisah untuk setiap kombinasi batch, strategi, dan ticker karena entry, TP, SL, serta status auditnya dapat berbeda.

## Kompatibilitas data

Database naik dari versi 4 ke 5 melalui migration non-destruktif. Kolom `auditTelegramSent` ditambahkan dengan nilai awal `false`; record, hasil audit, entry aktual, dan credential versi sebelumnya tetap dipertahankan.
