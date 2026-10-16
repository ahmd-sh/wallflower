package sh.ahmd.wallflower

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.session.TcpTransport
import sh.ahmd.wallflower.ui.WallflowerApp

class SessionViewModel : ViewModel() {
    val session = Session(TcpTransport(), viewModelScope)
}

class MainActivity : ComponentActivity() {
    private val vm: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WallflowerApp(vm.session) }
    }
}
