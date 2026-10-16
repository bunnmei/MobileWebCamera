package space.webkombinat.MobileWebCamera.stream

import java.net.Inet4Address
import java.net.NetworkInterface

enum class LinkKind(val label: String) {
    USB("USB"),
    WIFI("Wi-Fi"),
}

/** PC から接続できる端末のアドレス */
data class LocalAddress(val kind: LinkKind, val ip: String)

object NetworkUtils {

    /**
     * USB テザリングと Wi-Fi のアドレスだけを返す。
     * モバイルデータ (rmnet / ccmni 等) は PC から届かないので除外する。
     */
    fun localAddresses(): List<LocalAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                val kind = classify(nif.name) ?: return@flatMap emptyList()
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { addr -> addr.hostAddress?.let { LocalAddress(kind, it) } }
            }
            .distinct()
            .sortedBy { it.kind.ordinal }
    }.getOrDefault(emptyList())

    private fun classify(name: String): LinkKind? = when {
        // USB テザリング: 端末により rndis0 (旧) / ncm0 (Android 11+) / usb0
        name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("usb") -> LinkKind.USB
        name.startsWith("wlan") || name.startsWith("swlan") -> LinkKind.WIFI
        else -> null
    }
}
