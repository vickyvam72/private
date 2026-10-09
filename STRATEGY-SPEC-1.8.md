# Lumi Signal — Strategy Specification 1.8

## Prinsip evaluasi

- Seluruh indikator strategi memakai OHLCV, traded value, transaction frequency, dan broker summary Stockbit.
- Nilai transaksi tidak lagi diestimasi dari `close × volume`.
- Kriteria `DATA_UNAVAILABLE` tidak dihitung sebagai cocok dan membuat strategi tidak dapat lolos.
- Ambang 8/10 atau 9/10 tetap berlaku, tetapi bukti inti setiap strategi menjadi hard gate.
- Angka parameter adalah hipotesis awal yang harus diuji out-of-sample; bukan klaim win rate.

## Susunan 10 strategi

| No. | Strategi | Fase | Sumber Stockbit utama | Hard gate |
|---:|---|---|---|---|
| 1 | Quiet Accumulation | Akumulasi dini | OHLCV, value | RTV, RVOL, OBV/A-D |
| 2 | Absorption at Support | Akumulasi dini | OHLCV, broker 1D | Support, absorption candle, broker net buy |
| 3 | Broker Accumulation Persistence | Akumulasi dini | 10 broker snapshots | 10 sesi, top-3 positif 6/10, flow kumulatif |
| 4 | Frequency Creep | Aktivitas dini | Frequency, value, volume | RFREQ, RFREQ>RVOL, trade size, tren 3 sesi |
| 5 | Broker–Price Divergence | Akumulasi dini | OHLCV, broker 5D/10D | Harga datar/lemah, broker positif, persistence, A/D |
| 6 | Volatility Compression | Persiapan breakout | OHLCV | Base, ATR compression, BBW percentile |
| 7 | Shakeout–Spring Reclaim | Reversal | OHLCV, broker 1D | Spring, reclaim, volume, broker net buy |
| 8 | Markup Ignition | Breakout | OHLCV, value, frequency, broker | Breakout, RVOL, RTV, RFREQ |
| 9 | Reaccumulation After First Markup | Continuation | OHLCV, broker | Markup awal, konsolidasi, support lama, breakout kedua |
| 10 | Breakout Retest Confirmation | Konfirmasi breakout | OHLCV, broker 1D | Breakout, retest, resistance bertahan, reclaim high |

## Strategi 5 — Broker–Price Divergence

Tujuan: mencari penyerapan broker ketika harga belum mengonfirmasi kenaikan, sehingga sinyal tidak bergantung pada data free-float yang belum dapat dibuktikan.

1. Return 20 hari berada pada −10% sampai +5%.
2. Range 20 hari maksimal 20%.
3. Broker net buy kumulatif 10 hari positif.
4. Broker net buy 5 hari tetap positif.
5. Kelompok top-3 buyer net buy minimal 6 dari 10 sesi.
6. A/D Line menanjak ketika harga masih datar/lemah.
7. OBV tidak melemah.
8. Rata-rata CLV 10 hari positif.
9. Up-volume/down-volume minimal 1,2.
10. Tidak ada candle distribusi ekstrem terbaru: RVOL ≥4, return <−3%, dan CLV <−0,5.

Ketentuan lolos: minimal 8/10, dengan butir 1, 3, 5, dan 6 sebagai hard gate. Strategi ini tidak menyimpulkan identitas bandar; broker code tetap diperlakukan sebagai agregat aktivitas nasabah broker.

## Strategi 10 — Breakout Retest Confirmation

Tujuan: mencari entry setelah pasar membuktikan bahwa resistance lama telah berubah menjadi support, tanpa memerlukan histori replenishment orderbook.

1. Breakout valid terjadi 1–7 sesi sebelumnya.
2. Candle breakout ditutup di atas resistance 20 hari.
3. Volume breakout minimal 1,5× median volume 60 hari.
4. Retest menyentuh zona ±3% dari resistance lama.
5. Retest ditutup minimal pada 98% level resistance.
6. Volume retest maksimal 70% volume breakout.
7. Harga trigger menutup di atas high candle retest.
8. Candle trigger ditutup dekat high dengan CLV minimal 0,5.
9. OBV atau A/D Line bertahan/menguat.
10. Broker 1D tetap netral atau net buy.

Ketentuan lolos: minimal 9/10, dengan butir 1, 2, 4, 5, dan 7 sebagai hard gate. Entry berada di atas high retest; stop struktural berada di bawah low retest dengan buffer ATR.

## Rekonstruksi strategi yang hampir penuh

### Strategi 2

Broker confirmation menggunakan window 1D pada sesi pengujian terkini. Support, volume absorption, midpoint close, lower shadow, CLV, dan A/D dihitung dari OHLCV Stockbit.

### Strategi 3

Agregat 10D tidak lagi dianggap bukti persistence. Lumi mengambil sepuluh snapshot broker harian dan menghitung berapa hari kelompok top-3 buyer benar-benar net buy.

### Strategi 4

RFREQ 5/60, RFREQ terhadap RVOL, average trade size, dan kenaikan frekuensi beruntun dihitung dari frequency dan traded value Stockbit. Kriteria berita yang tidak lengkap diganti dengan tes bahwa peningkatan bukan lonjakan satu hari.

### Strategi 8

RFREQ 1/60 menjadi kriteria nyata dari historical summary Stockbit, bukan `DATA_UNAVAILABLE`.

## Batas data global

Formula kesepuluh strategi dapat dihitung dari data Stockbit yang ditemukan, tetapi tiga pemeriksaan global belum boleh diklaim sempurna: status UMA/FCA dan special notation perlu parser status yang tervalidasi; corporate action perlu cakupan event yang lengkap; median spread 20 sesi perlu arsip snapshot sendiri. Sampai validasi tersebut selesai, Lumi harus menampilkannya sebagai keterbatasan global, bukan menganggapnya lolos.
