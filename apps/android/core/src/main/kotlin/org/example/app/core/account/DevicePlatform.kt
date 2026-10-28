package org.example.app.core.account

/** The kind of device, with the wire value the server stores. */
enum class DevicePlatform(val wire: String) {
    IPhone("iphone"), Mac("mac"), Android("android"),
    /** A platform this app does not know; shown as "Other device", never as a familiar one. */
    Other("other");

    companion object {
        fun fromWire(value: String): DevicePlatform? = entries.firstOrNull { it.wire == value }
    }
}
