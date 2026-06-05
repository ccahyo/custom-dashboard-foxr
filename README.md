# Custom Speedometer FOX-R

Dashboard real-time khusus untuk sepeda motor listrik FOX-R yang menggunakan controller Votol, gateway CAN ESP32, runtime Android, dan dashboard HTML yang sepenuhnya dapat dikustomisasi.

Project ini menggunakan firmware ESP32 CAN gateway dari zexry619, lalu menambahkan aplikasi Android sebagai runtime dashboard modern yang mendukung telemetry melalui BLE dan WiFi.

---

## Fitur

- Speedometer real-time
- Monitoring RPM
- Monitoring tegangan baterai
- Monitoring arus baterai
- Monitoring daya/power
- State of Charge (SOC)
- Temperatur controller
- Temperatur motor / BLDC
- Temperatur baterai
- Monitoring tegangan sel baterai
- Statistik tegangan sel baterai
- Monitoring kesehatan baterai
- Monitoring charger
- Telemetry melalui BLE
- Telemetry melalui WiFi
- Speed berbasis GPS sebagai opsi tambahan
- Tampilan waktu pada dashboard
- Dashboard HTML yang bebas dikustomisasi
- Runtime berbasis Android
- Internal WebSocket bridge
- Kompatibel dengan firmware ESP32 OTA

---

# Arsitektur Sistem

```text
Votol Controller
        │
        │ CAN Bus
        ▼
ESP32 Gateway
        │
        ├── BLE
        │
        └── WiFi
                │
                ▼
Aplikasi Android
                │
                │ Internal WebSocket
                ▼
dashboard.html
```

ESP32 berfungsi sebagai jembatan CAN-to-Telemetry.

Aplikasi Android menerima telemetry dari ESP32 melalui BLE atau WiFi, menormalisasi struktur data, lalu meneruskannya ke dashboard melalui internal WebSocket server.

File `dashboard.html` tidak perlu mengetahui detail implementasi BLE, WiFi, CAN Bus, atau firmware ESP32. Dashboard hanya membaca JSON yang sudah dinormalisasi oleh aplikasi Android.

---

# Cara Mulai

## 1. Flash Firmware ESP32

Project ini bergantung pada firmware ESP32 dari:

👉 https://github.com/zexry619/votol-esp32-can-bus/tree/beta

Ikuti instruksi pada repository firmware tersebut untuk:

- Setup ESP32
- Wiring CAN Bus
- Wiring controller Votol
- Konfigurasi BMS
- Setup OTA

Setelah firmware berhasil ditanam, ESP32 akan menyediakan:

```text
BLE Name:
Votol_BLE
```

dan mengirim telemetry melalui:

```text
BLE
WiFi
```

---

# Protokol JSON ESP32

Untuk menghemat bandwidth BLE, firmware ESP32 mengirimkan paket JSON ringkas dengan key pendek.

Ada dua jenis paket utama:

```text
fast
full
```

---

## Paket Fast

Contoh:

```json
{
  "type": "fast",
  "r": 1500,
  "s": 45,
  "m": "DRIVE",
  "v": 72.5,
  "a": 18.4,
  "p": 1334,
  "sc": 84,
  "t": {
    "c": 42,
    "m": 55,
    "b": 31
  },
  "cr": 100,
  "hb": 1234
}
```

### Field Fast

| Key | Deskripsi |
|------|------------|
| r | RPM motor |
| s | Kecepatan kendaraan dari ESP32/CAN |
| m | Mode berkendara |
| v | Tegangan baterai |
| a | Arus baterai |
| p | Daya baterai |
| sc | State of Charge / persentase baterai |
| t.c | Temperatur controller |
| t.m | Temperatur motor / BLDC |
| t.b | Temperatur baterai |
| cr | CAN rate |
| hb | Heartbeat counter |

---

## Paket Full

Paket `full` berisi semua field dari paket `fast`, ditambah informasi baterai dan BMS yang lebih lengkap.

### Kesehatan Baterai

```json
"h": {
  "soh": 98,
  "cyc": 120,
  "rc": 38.5,
  "fc": 40.0
}
```

| Key | Deskripsi |
|------|------------|
| soh | State of Health / kesehatan baterai |
| cyc | Jumlah siklus pengisian |
| rc | Kapasitas tersisa |
| fc | Kapasitas penuh |

### Tegangan Sel

```json
"cells": [4100, 4098, 4097]
```

| Key | Deskripsi |
|------|------------|
| cells | Tegangan masing-masing sel baterai |

### Statistik Tegangan Sel

```json
"cvs": {
  "hi": 4100,
  "hiC": 1,
  "lo": 4097,
  "loC": 12,
  "av": 4099
}
```

| Key | Deskripsi |
|------|------------|
| hi | Tegangan sel tertinggi |
| hiC | Nomor sel dengan tegangan tertinggi |
| lo | Tegangan sel terendah |
| loC | Nomor sel dengan tegangan terendah |
| av | Rata-rata tegangan sel |

### Statistik Temperatur

```json
"ts": {
  "max": 42,
  "maxC": 6,
  "min": 31,
  "minC": 1
}
```

| Key | Deskripsi |
|------|------------|
| max | Temperatur tertinggi |
| maxC | Sensor/sel dengan temperatur tertinggi |
| min | Temperatur terendah |
| minC | Sensor/sel dengan temperatur terendah |

### Informasi Balancing

```json
"b": {
  "md": 1,
  "st": 1,
  "cells": [0, 1, 0, 1]
}
```

| Key | Deskripsi |
|------|------------|
| md | Mode balancing |
| st | Status balancing |
| cells | Sel yang sedang aktif balancing |

### Informasi Charger

```json
"chr": {
  "on": 1,
  "v": 84.0,
  "a": 5.0,
  "ori": 1
}
```

| Key | Deskripsi |
|------|------------|
| on | Status charger tersambung |
| v | Tegangan charger |
| a | Arus charger |
| ori | Flag charger original |

### Informasi BMS

```json
"bms": {
  "hw": "1.0",
  "fw": "2.3"
}
```

| Key | Deskripsi |
|------|------------|
| hw | Versi hardware BMS |
| fw | Versi firmware BMS |

---

# Normalisasi JSON di Android

Aplikasi Android **tidak langsung meneruskan JSON asli ESP32** ke dashboard.

Aplikasi Android mengubah key pendek dari ESP32 menjadi key yang lebih mudah dibaca dan lebih nyaman dipakai saat membuat desain dashboard.

Contoh hasil normalisasi:

```json
{
  "speed": 45,
  "rpm": 1500,
  "volts": 72.5,
  "amps": 18.4,
  "power": 1334,
  "soc": 84,
  "mode": "DRIVE",
  "gpsSpeed": 44.8,
  "temps": {
    "ctrl": 42,
    "motor": 55,
    "batt": 31
  }
}
```

Dengan cara ini, `dashboard.html` tetap sederhana dan tidak tergantung pada format internal firmware ESP32.

---

# API Dashboard

Jika ingin membuat desain dashboard sendiri, gunakan field-field berikut.

## Field Dasar

| Field | Deskripsi |
|---------|------------|
| speed | Kecepatan kendaraan dari ESP32/CAN |
| gpsSpeed | Kecepatan kendaraan dari GPS Android |
| rpm | RPM motor |
| volts | Tegangan baterai |
| amps | Arus baterai |
| power | Daya baterai |
| soc | Persentase baterai |
| mode | Mode berkendara saat ini |

---

## Speed dari GPS

Field `gpsSpeed` berasal dari sensor GPS perangkat Android.

Jika tersedia, dashboard dapat menggunakan `gpsSpeed` sebagai sumber kecepatan alternatif atau prioritas utama. Ini berguna ketika:

- data speed dari CAN belum tersedia,
- speed dari controller kurang stabil,
- ingin membandingkan speed CAN dengan speed GPS,
- ingin mode dashboard yang lebih dekat dengan konsep navigasi.

Contoh penggunaan di `dashboard.html`:

```javascript
const speed = data.gpsSpeed ?? data.speed ?? 0;
```

Jika ingin menampilkan indikator bahwa speed berasal dari GPS:

```javascript
if (data.gpsSpeed !== undefined && data.gpsSpeed !== null) {
    speed = data.gpsSpeed;
    sourceIcon.innerText = "📡";
    sourceIcon.style.display = "block";
} else {
    speed = data.speed ?? 0;
    sourceIcon.innerText = "";
    sourceIcon.style.display = "none";
}
```

Catatan:

- `gpsSpeed` hanya tersedia jika izin lokasi diberikan.
- GPS membutuhkan lock satelit, sehingga nilai speed bisa terlambat muncul setelah aplikasi dibuka.
- Pada area tertutup, GPS speed bisa tidak tersedia atau kurang stabil.
- Jika GPS tidak tersedia, dashboard sebaiknya fallback ke field `speed`.

---

## Informasi Waktu

Dashboard juga dapat menampilkan waktu lokal dari perangkat Android/WebView.

Contoh tampilan:

```text
Time
12:08
```

Contoh implementasi JavaScript:

```javascript
function updateDateTime() {
    const now = new Date();

    const hour = String(now.getHours()).padStart(2, "0");
    const minute = String(now.getMinutes()).padStart(2, "0");

    const hm = document.getElementById("hours-minutes");

    if (hm) {
        hm.innerText = `${hour}:${minute}`;
    }
}

updateDateTime();
setInterval(updateDateTime, 1000);
```

Catatan:

- Waktu berasal dari perangkat Android, bukan dari ESP32.
- Fitur ini tidak memerlukan perubahan firmware ESP32.
- Pastikan elemen HTML dengan `id="hours-minutes"` tersedia di `dashboard.html`.

---

## Field Temperatur

| Field | Deskripsi |
|---------|------------|
| temps.ctrl | Temperatur controller |
| temps.motor | Temperatur motor / BLDC |
| temps.batt | Temperatur baterai |

## Kesehatan Baterai

| Field | Deskripsi |
|---------|------------|
| health.soh | State of Health / kesehatan baterai |
| health.cycles | Jumlah siklus pengisian |
| health.remcap | Kapasitas tersisa |
| health.fullcap | Kapasitas penuh |

## Statistik Tegangan Sel

| Field | Deskripsi |
|---------|------------|
| cellvoltstats.highest | Tegangan sel tertinggi |
| cellvoltstats.lowest | Tegangan sel terendah |
| cellvoltstats.average | Rata-rata tegangan sel |
| cellvoltstats.delta | Selisih tegangan sel tertinggi dan terendah |

## Charger

| Field | Deskripsi |
|---------|------------|
| charger.on | Charger tersambung |
| charger.volts | Tegangan charger |
| charger.amps | Arus charger |
| charger.original | Charger original |

## BMS

| Field | Deskripsi |
|---------|------------|
| bmsinfo.hwver | Versi hardware BMS |
| bmsinfo.fwver | Versi firmware BMS |

---

# Menyesuaikan Dashboard

Semua elemen visual berada di:

```text
dashboard.html
```

Anda bebas mengubah:

- Layout
- Tema
- Gauge
- Warna
- Animasi
- Widget
- Grafik
- Font
- Indikator speed GPS
- Tampilan waktu

Tidak perlu mengubah firmware ESP32.

Tidak perlu mengubah kode Android selama dashboard masih membaca field JSON hasil normalisasi.

---

# BLE vs WiFi

## BLE

Kelebihan:

- Tidak membutuhkan hotspot
- Konsumsi daya lebih rendah
- Koneksi sederhana
- Cocok untuk paket `fast`

Kekurangan:

- Bandwidth lebih kecil
- Paket bisa terfragmentasi
- Perlu proses reassembly JSON di Android

## WiFi

Kelebihan:

- Bandwidth lebih besar
- Latency lebih rendah
- Pengiriman paket lengkap lebih stabil
- Lebih cocok untuk data `full`

Kekurangan:

- Konsumsi daya lebih tinggi
- Perlu koneksi WiFi ke ESP32

---

# Roadmap

Pengembangan berikutnya dapat mencakup:

- Dukungan Android Auto
- Integrasi GPS lebih lanjut
- Trip computer
- Statistik perjalanan
- CAN logger
- Perekaman telemetry
- Theme manager
- Multi bahasa
- Sinkronisasi cloud
- Mode dashboard ringkas
- Mode dashboard navigasi

---

# Kredit

### ESP32 CAN Gateway

Dibuat oleh:

https://github.com/zexry619/votol-esp32-can-bus

### Pengembangan Dashboard

Chandra Cahyo

---

# Lisensi

Project ini dirilis dengan lisensi non-komersial.

Penggunaan pribadi, edukasi, dan hobi diperbolehkan.

Penggunaan komersial, penjualan ulang, redistribusi dalam produk komersial, atau layanan berbayar yang berbasis project ini membutuhkan izin tertulis terlebih dahulu dari pembuat.
