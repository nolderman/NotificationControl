package com.notificationcontrol

// Not a data class: Regex doesn't implement equals/hashCode, so we use id-based equality
class AlertRule(
    val id: String,
    val name: String,
    val packageName: String,
    val keyword: Regex,
    val defaultSound: Boolean = false,
    val defaultVibrate: Boolean = false
) {
    override fun equals(other: Any?) = other is AlertRule && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        val PREDEFINED = listOf(
            AlertRule(
                id = "person_seen",
                name = "Person Seen",
                packageName = "com.google.android.apps.chromecast.app",
                keyword = Regex("\\bperson\\s+seen\\b", RegexOption.IGNORE_CASE),
                defaultSound = true
            ),
            AlertRule(
                id = "vehicle_seen",
                name = "Vehicle Seen",
                packageName = "com.google.android.apps.chromecast.app",
                keyword = Regex("\\bvehicle\\b", RegexOption.IGNORE_CASE)
            )
        )

        fun findById(id: String): AlertRule? = PREDEFINED.find { it.id == id }
    }
}
