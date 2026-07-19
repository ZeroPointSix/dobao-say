package com.zeropointsix.dobaosay.doubao

internal object DoubaoConstants {
    const val REGISTER_URL = "https://log.snssdk.com/service/2/device_register/"
    const val SETTINGS_URL = "https://is.snssdk.com/service/settings/v3/"
    const val WEBSOCKET_URL = "wss://frontier-audio-ime-ws.doubao.com/ocean/api/v1/ws"

    const val AID = 401734
    const val APP_NAME = "oime"
    const val VERSION_CODE = 100102018
    const val VERSION_NAME = "1.1.2"
    const val CHANNEL = "official"
    const val PACKAGE_NAME = "com.bytedance.android.doubaoime"

    const val USER_AGENT =
        "com.bytedance.android.doubaoime/100102018 " +
            "(Linux; U; Android 16; en_US; Pixel 7 Pro; Build/BP2A.250605.031.A2; " +
            "Cronet/TTNetVersion:94cf429a 2025-11-17 QuicVersion:1f89f732 2025-05-08)"

    val DEFAULT_DEVICE =
        DoubaoDeviceProfile(
            devicePlatform = "android",
            os = "android",
            osApi = "34",
            osVersion = "16",
            deviceType = "Pixel 7 Pro",
            deviceBrand = "google",
            deviceModel = "Pixel 7 Pro",
            resolution = "1080*2400",
            dpi = "420",
            language = "zh",
            timezone = 8,
            access = "wifi",
            rom = "UP1A.231005.007",
            romVersion = "UP1A.231005.007",
        )
}
