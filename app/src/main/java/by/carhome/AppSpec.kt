package by.carhome

enum class TileKind { PLAIN, CLIMATE, CARPLAY, ANDROID_AUTO, ALL_APPS }

/** label/letter are string resources; letter/color: fallback icon when the app is not installed. */
data class AppSpec(
    val kind: TileKind,
    val pkg: String,
    val label: Int,
    val letter: Int = 0,
    val color: Int = R.color.fb_other,
)

/** Bottom row of the home screen, in list order. */
object Apps {
    val list = listOf(
        AppSpec(TileKind.PLAIN, "ru.yandex.yandexnavi", R.string.app_navigator, R.string.app_navigator_letter, R.color.fb_nav),
        AppSpec(TileKind.PLAIN, "ru.yandex.music", R.string.app_music, R.string.app_music_letter, R.color.fb_music),
        AppSpec(TileKind.CARPLAY, "com.neusoft.carplay", R.string.app_carplay),
        AppSpec(TileKind.CLIMATE, "ecarx.hvac.app", R.string.app_climate),
        AppSpec(TileKind.ANDROID_AUTO, "com.andrerinas.headunitrevived", R.string.app_android_auto),
        // Opens the stock app list (ecarx.launcher3/.AppPane), see HomeActivity.openAllApps().
        AppSpec(TileKind.ALL_APPS, "ecarx.launcher3", R.string.app_all),
    )
}
