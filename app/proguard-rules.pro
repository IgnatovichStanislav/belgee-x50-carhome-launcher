# ECARX adapt API and android.car are reached by reflection via class-name strings
# (framework classes, not in our dex), so nothing of ours needs keeping beyond the defaults.
-dontwarn com.ecarx.**
-dontwarn android.car.**
