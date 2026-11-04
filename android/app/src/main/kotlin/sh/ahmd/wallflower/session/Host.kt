package sh.ahmd.wallflower.session

import android.content.SharedPreferences

/**
 * Where the gateway runs, and the multiplexer ([mux], tmux or rex) whose
 * session it serves. [dev] uses `wallflower listen` through adb reverse
 * instead of ssh.
 */
data class Host(
    val address: String = "100.116.36.118",
    val port: Int = 22,
    val user: String = "ahmedshaikh",
    val command: String = "~/go/bin/wallflower serve",
    val mux: String = "tmux",
    val dev: Boolean = false,
) {
    fun save(prefs: SharedPreferences) = prefs.edit()
        .putString("address", address)
        .putInt("port", port)
        .putString("user", user)
        .putString("command", command)
        .putString("mux", mux)
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
                mux = prefs.getString("mux", d.mux) ?: d.mux,
                dev = prefs.getBoolean("dev", false),
            )
        }
    }
}
