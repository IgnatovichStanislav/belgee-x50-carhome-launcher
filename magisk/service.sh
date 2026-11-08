#!/system/bin/sh
# BG Car Home: runtime permissions + one-time AOT compile for fast cold start.
MODDIR=${0%/*}
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 5
for p in android.permission.ACCESS_FINE_LOCATION \
         android.car.permission.CAR_SPEED android.car.permission.CAR_ENERGY \
         android.car.permission.CAR_EXTERIOR_ENVIRONMENT android.car.permission.CAR_INFO \
         android.car.permission.CAR_POWERTRAIN; do
  pm grant by.carhome $p 2>/dev/null
done
# Compile to native code once per APK version (no JIT warm-up on launch).
APK="$MODDIR/system/priv-app/BGCarHome/BGCarHome.apk"
STAMP="$MODDIR/.compiled"
SIG="$(stat -c %s-%Y "$APK" 2>/dev/null)"
if [ "$(cat "$STAMP" 2>/dev/null)" != "$SIG" ]; then
  cmd package compile -m speed -f by.carhome >/dev/null 2>&1 && echo "$SIG" > "$STAMP"
fi
