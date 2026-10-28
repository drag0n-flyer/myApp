package org.example.app.core.account

/** The kind of device, with the wire value the server stores. */
enum class DevicePlatform(val wire: String) {
    IPhone("iphone"), Mac("mac"), Android("android");

    companion object {
        fun fromWire(value: String): DevicePlatform? = entries.firstOrNull { it.wire == value }
    }
}
