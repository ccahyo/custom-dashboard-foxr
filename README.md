# Custom Speedometer FOX-R

A custom real-time dashboard for the FOX-R electric motorcycle powered by a Votol controller, ESP32 CAN gateway, Android runtime, and a fully customizable HTML dashboard.

The project uses the excellent ESP32 CAN gateway created by zexry619 and focuses on providing a modern, highly customizable dashboard experience while remaining compatible with both BLE and WiFi telemetry.

---

## Features

- Real-time speedometer
- RPM monitoring
- Battery voltage monitoring
- Battery current monitoring
- Power monitoring
- State of Charge (SOC)
- Controller temperature
- Motor temperature
- Battery temperature
- Cell voltage monitoring
- Cell voltage statistics
- Battery health monitoring
- Charger monitoring
- BLE telemetry
- WiFi telemetry
- Fully customizable HTML dashboard
- Android-based runtime
- Internal WebSocket bridge
- OTA-compatible ESP32 firmware

---

# System Architecture

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
Android Application
                │
                │ Internal WebSocket
                ▼
dashboard.html
```

The ESP32 acts as a CAN-to-Telemetry bridge.

The Android application receives telemetry from ESP32 through BLE or WiFi, normalizes the data structure, and forwards it to the dashboard through an internal WebSocket server.

The dashboard itself is completely independent from BLE, WiFi, CAN Bus, and ESP32 implementation details.

---

# Getting Started

## 1. Flash ESP32 Firmware

This project depends on the ESP32 firmware from:

👉 https://github.com/zexry619/votol-esp32-can-bus/tree/beta

Follow the firmware repository instructions for:

- ESP32 setup
- CAN Bus wiring
- Votol controller wiring
- BMS configuration
- OTA setup

After flashing, the ESP32 should expose:

```text
BLE Name:
Votol_BLE
```

and telemetry through:

```text
BLE
WiFi
```

---

# ESP32 JSON Protocol

To minimize BLE bandwidth usage, the ESP32 firmware transmits compact JSON packets using short keys.

Two packet types are used:

```text
fast
full
```

---

## Fast Packet

Example:

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

### Fast Fields

| Key | Description |
|------|------------|
| r | Motor RPM |
| s | Vehicle speed |
| m | Drive mode |
| v | Battery voltage |
| a | Battery current |
| p | Battery power |
| sc | State of Charge |
| t.c | Controller temperature |
| t.m | Motor temperature |
| t.b | Battery temperature |
| cr | CAN rate |
| hb | Heartbeat counter |

---

## Full Packet

Full packets include all fast fields plus extended battery and BMS information.

---

### Battery Health

```json
"h": {
  "soh": 98,
  "cyc": 120,
  "rc": 38.5,
  "fc": 40.0
}
```

| Key | Description |
|------|------------|
| soh | State of Health |
| cyc | Charge cycles |
| rc | Remaining capacity |
| fc | Full capacity |

---

### Cell Voltages

```json
"cells": [
  4100,
  4098,
  4097
]
```

| Key | Description |
|------|------------|
| cells | Voltage of each battery cell |

---

### Cell Voltage Statistics

```json
"cvs": {
  "hi": 4100,
  "hiC": 1,
  "lo": 4097,
  "loC": 12,
  "av": 4099
}
```

| Key | Description |
|------|------------|
| hi | Highest cell voltage |
| hiC | Highest cell number |
| lo | Lowest cell voltage |
| loC | Lowest cell number |
| av | Average cell voltage |

---

### Temperature Statistics

```json
"ts": {
  "max": 42,
  "maxC": 6,
  "min": 31,
  "minC": 1
}
```

| Key | Description |
|------|------------|
| max | Highest temperature |
| maxC | Highest temperature sensor |
| min | Lowest temperature |
| minC | Lowest temperature sensor |

---

### Balance Information

```json
"b": {
  "md": 1,
  "st": 1,
  "cells": [0,1,0,1]
}
```

| Key | Description |
|------|------------|
| md | Balance mode |
| st | Balance status |
| cells | Active balancing cells |

---

### Charger Information

```json
"chr": {
  "on": 1,
  "v": 84.0,
  "a": 5.0,
  "ori": 1
}
```

| Key | Description |
|------|------------|
| on | Charger connected |
| v | Charger voltage |
| a | Charger current |
| ori | Original charger flag |

---

### BMS Information

```json
"bms": {
  "hw": "1.0",
  "fw": "2.3"
}
```

| Key | Description |
|------|------------|
| hw | BMS hardware version |
| fw | BMS firmware version |

---

# Android JSON Normalization

The Android application does **not** forward the original ESP32 JSON directly.

Instead, it converts short keys into a more readable structure for dashboard development.

Example:

```json
{
  "speed": 45,
  "rpm": 1500,
  "volts": 72.5,
  "amps": 18.4,
  "power": 1334,
  "soc": 84,
  "mode": "DRIVE",
  "temps": {
    "ctrl": 42,
    "motor": 55,
    "batt": 31
  }
}
```

This keeps dashboard code simple and independent from ESP32 protocol changes.

---

# Dashboard API

If you want to create your own dashboard design, these are the primary fields you should use.

---

## Basic Fields

| Field | Description |
|---------|------------|
| speed | Vehicle speed |
| rpm | Motor RPM |
| volts | Battery voltage |
| amps | Battery current |
| power | Battery power |
| soc | Battery percentage |
| mode | Current drive mode |

---

## Temperature Fields

| Field | Description |
|---------|------------|
| temps.ctrl | Controller temperature |
| temps.motor | Motor temperature |
| temps.batt | Battery temperature |

---

## Battery Health

| Field | Description |
|---------|------------|
| health.soh | State of Health |
| health.cycles | Charge cycles |
| health.remcap | Remaining capacity |
| health.fullcap | Full capacity |

---

## Cell Voltage Statistics

| Field | Description |
|---------|------------|
| cellvoltstats.highest | Highest cell voltage |
| cellvoltstats.lowest | Lowest cell voltage |
| cellvoltstats.average | Average cell voltage |

---

## Charger

| Field | Description |
|---------|------------|
| charger.on | Charger connected |
| charger.volts | Charger voltage |
| charger.amps | Charger current |

---

## BMS

| Field | Description |
|---------|------------|
| bmsinfo.hwver | Hardware version |
| bmsinfo.fwver | Firmware version |

---

# Customizing the Dashboard

All visual elements are contained inside:

```text
dashboard.html
```

You are free to modify:

- Layout
- Themes
- Gauges
- Colors
- Animations
- Widgets
- Graphs

No ESP32 firmware changes are required.

No Android code changes are required.

As long as your dashboard consumes the normalized JSON fields, the backend remains unchanged.

---

# BLE vs WiFi

## BLE

Advantages:

- No hotspot required
- Lower power consumption
- Easy connection

Disadvantages:

- Lower bandwidth
- Possible packet fragmentation

---

## WiFi

Advantages:

- Higher bandwidth
- Lower latency
- More reliable full packet delivery

Disadvantages:

- Higher power consumption

---

# Roadmap

Future improvements may include:

- Android Auto support
- GPS integration
- Trip computer
- Ride statistics
- CAN logger
- Telemetry recording
- Theme manager
- Multi-language support
- Cloud synchronization

---

# Credits

### ESP32 CAN Gateway

Created by:

https://github.com/zexry619/votol-esp32-can-bus

### Dashboard Development

Chandra Cahyo

---

# License

This project is released under a non-commercial license.

Personal, educational, and hobbyist use is permitted.

Commercial use, resale, redistribution in commercial products, or paid services based on this project require prior written permission from the author.
