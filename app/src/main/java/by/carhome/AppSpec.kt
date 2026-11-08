package by.carhome

enum class TileKind { PLAIN, CLIMATE, CARPLAY, ANDROID_AUTO, ALL_APPS }

/** letter/color: fallback icon when the app is not installed. */
data class AppSpec(
    val kind: TileKind,
    val pkg: String,
    val label: String,
    val letter: String = "",
    val color: Int = R.color.fb_other,
)

/** Bottom row of the home screen, in list order. */
object Apps {
    val list = listOf(
        AppSpec(TileKind.PLAIN, "ru.yandex.yandexnavi", "Навигатор", "Н", R.color.fb_nav),
        AppSpec(TileKind.PLAIN, "ru.yandex.music", "Яндекс\nМузыка", "М", R.color.fb_music),
        AppSpec(TileKind.CARPLAY, "com.neusoft.carplay", "Apple CarPlay"),
        AppSpec(TileKind.CLIMATE, "ecarx.hvac.app", "Климат"),
        AppSpec(TileKind.ANDROID_AUTO, "com.andrerinas.headunitrevived", "Android Auto"),
        // Opens the stock app list (ecarx.launcher3/.AppPane), see HomeActivity.openAllApps().
        AppSpec(TileKind.ALL_APPS, "ecarx.launcher3", "Все приложения"),
    )
}
