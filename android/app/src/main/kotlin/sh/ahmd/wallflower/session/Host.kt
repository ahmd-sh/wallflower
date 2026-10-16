package sh.ahmd.wallflower.session

import android.content.SharedPreferences

/** Where the gateway runs. [dev] uses `wallflower listen` through adb reverse instead of ssh. */
data class Host(
    val address: String = "100.116.36.118",
    val port: Int = 22,
    val user: String = "ahmedshaikh",
    val command: String = "~/go/bin/wallflower serve",
    val dev: Boolean = false,
) {
    fun save(prefs: SharedPreferences) = prefs.edit()
        .putString("address", address)
        .putInt("port", port)
        .putString("user", user)
        .putString("command", command)
        .putBoolean("dev", dev)
        .putBoolean("configured", true)
        .apply()

    companion object {
        fun load(prefs: SharedPreferences): Host? {
            if (!prefs.getBoolean("configured", false)) return null
            val d = Host()
            return Host(
                address = prefs.getString("address", d.address) ?: d.address,
                port = prefs.getInt("port", d.port),
                user = prefs.getString("user", d.user) ?: d.user,
                command = prefs.getString("command", d.command) ?: d.command,
                dev = prefs.getBoolean("dev", false),
            )
        }
    }
}
