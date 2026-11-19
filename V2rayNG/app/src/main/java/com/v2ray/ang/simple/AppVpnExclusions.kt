package com.v2ray.ang.simple

/** Package identities, independent of both address lists and panel settings. */
object AppVpnExclusions {
    const val STAGED_KEY = "simple.vpn.excluded.apps.v1"
    fun normalized(packages: List<String>): List<String> {
        require(packages.size <= 2000) { "Слишком много приложений." }
        return packages.map { value ->
            require(value.length <= 255 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*").matches(value)) {
                "Неверное имя пакета приложения."
            }
            value
        }.distinct().sorted()
    }
}
