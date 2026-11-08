# BG Car Home

[Русская версия](README.ru.md)

A custom home screen for the stock head unit of the **Belgee X50** (Geely Coolray, SX11, **Proton X50**). It shows live car
data, switches the heaters, controls the climate and launches Apple CarPlay and Android Auto. Built for my
own car, free to use and to fork.

| Day | Night |
|---|---|
| ![Day theme](screenshots/hu-day.png) | ![Night theme](screenshots/hu-night.png) |

> Unofficial project, not affiliated with Belgee, Geely or ECARX. Installing it needs root on the head unit;
> you do it at your own risk. The interface is in Russian for now.

## What it does

**Live car data.** Speed, gear (P R N D), range and fuel in litres, odometer and trip, distance to the next
service, battery voltage (warning threshold depends on whether the engine runs: 11.8 V off, 12.0 V on),
coolant, oil, gearbox and tyre warnings from the cluster, driver and passenger seat belts, open doors, trunk
and hood, AutoHold (off, armed, holding).

**Controls.** Heated steering wheel, washer nozzles and windshield as on-screen buttons. A climate card with
temperature, airflow, fan speed, A/C, AUTO and recirculation; tapping it opens the stock climate app.

**Phone projection.** Apple CarPlay and Android Auto tiles show the real connection status and open the
projection on tap. Android Auto runs through [Open Headunit](https://github.com/andreknieriem/open-headunit);
its tile shows «Подключение…» (connecting), «Подключён» (connected) and «Нет связи» (link lost).

**Day and night themes.** Switched automatically at sunrise and sunset, because the car's own day/night signal
reads "night" all day on this model. Long-press the date to cycle Auto → Day → Night. The head unit's GEELY font
is extended with Cyrillic from Tektur (OFL). Full screen, no status bar.

**Drive recorder (optional).** Long-press the clock to log every change of the car signals to a CSV file.
It is how the signals in this app were decoded. See [Drive recorder](#drive-recorder).

Easter egg: tap the clock 7 times.

## Requirements

| | |
|---|---|
| Car | Belgee X50, Luxury trim (the middle one of Comfort / Luxury / Flagship) |
| Head unit | ECARX IHU730P, Android 9, 1920×720 screen |
| Firmware | ECARX_E02_V1.3 (Android 9, build 86) + [Lunaris](https://lunaris-firmware.ru) 2.01 |
| Root | Magisk, to install the app as a system (priv-app) app |
| Computer | ADB over Wi‑Fi or USB, for updates |

Why root: car data and heater control are only available to system apps holding `CAR_VENDOR_EXTENSION` and
`CONTROL_CAR_CLIMATE`.

For Android Auto you also need [Open Headunit](https://github.com/andreknieriem/open-headunit) 3.5.0
(a regular app) and an Android phone with Android Auto.

## Install

1. Download `bg-car-home-magisk-module.zip` from [Releases](../../releases). The APK is inside.
2. Copy the zip to the head unit, install it in Magisk → Modules and reboot.
3. Press Home and choose BG Car Home as the home app.

Updates without a reboot: `adb install -r bg-car-home-1.0.0-release.apk` (also attached to each release).
The module is only needed for the first install and when the app gets new system permissions.

Roll back: remove the "BG Car Home launcher" module in Magisk and reboot. The stock launcher stays in place.

## Drive recorder

Off by default. It exists to find and decode new car signals.

- **Turning it on:** long-press the clock; a red «● Запись данных» badge appears. If you leave it on, it
  resumes after a reboot; if you turn it off, it stays off.
- **Who can start it:** only the app itself. The recorder service is not exported to other apps.
- **What is logged:** changes of car signals (speed, gear, doors, climate, heaters, warnings and so on) with
  timestamps.
- **What is never logged:** VIN, ICCID, IMEI, Bluetooth MAC and GPS coordinates. These signals are filtered
  out in code (`PRIVATE_PROPS`).
- **Where it goes:** `/sdcard/Android/data/by.carhome/files/drive-YYYYMMDD-HHMMSS.csv`, a new file for every
  ignition cycle. Nowhere else: the app has no internet permission. Only you can pull the files, over ADB.
- **Deleting:** delete the files in that folder or uninstall the app.

## Not tested yet

- AutoHold: "armed" and "holding" are decoded from a single drive and need another check.
- Hood: the signal is known, opening the hood was never recorded.
- Passenger seat belt with nobody on the seat.
- Android Auto works, but the USB link drops now and then (one cable tested). Wireless mode not tested.
- Other trims, firmwares, the Geely Coolray and the Proton X50 (Malaysia) with the same head unit.

## Known issues

- The Home button on the system nav bar opens the stock launcher, not BG Car Home.
- The stock QDLink app (Q‑Drive Link) grabs Android phones on USB. Disable it with `pm disable-user`; it still
  starts after a reboot and sometimes gets in the way of Android Auto.
- The system nav bar on the left does not switch to the light theme.
- No outside temperature, RPM or instant fuel consumption: the head unit does not receive them or they read
  as garbage.

## Plans

- LSPosed module: Home button → BG Car Home, no pull-down shade, no CarPlay pop-up hints.
- Get rid of QDLink for good.
- Light theme for the system nav bar.
- Climate presets "Summer" and "Winter" for remote engine start.
- Sync day/night with Android Auto.
- Localization: Russian, English, Chinese and Malay, chosen at build time.
- Tests (unit, day/night screenshots, replay of recorded drives) and CI.
- Better car pictures.

## License

The code is [MIT](LICENSE).

Third-party material is not covered by MIT and is listed in [NOTICE](NOTICE): the CarPlay icons from the
stock ECARX launcher, the airflow icon from LunarisApp, the Android Auto icon (a Google trademark), Apple CarPlay
(an Apple trademark), the dealer photo our car picture is based on, the GEELY font (Geely) and Tektur ([SIL OFL 1.1](licenses/Tektur-OFL.txt)).
