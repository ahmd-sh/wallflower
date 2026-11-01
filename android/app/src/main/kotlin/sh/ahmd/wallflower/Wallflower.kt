package sh.ahmd.wallflower

import android.app.Application
import kotlinx.coroutines.MainScope

/** The app's process, which holds the [Connection] for as long as it lives. */
class Wallflower : Application() {
    lateinit var connection: Connection
        private set

    override fun onCreate() {
        super.onCreate()
        connection = Connection(this, MainScope())
    }
}

val android.content.Context.connection: Connection get() = (applicationContext as Wallflower).connection
