package app.medicinecabinet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.medicinecabinet.ui.CabinetApp
import app.medicinecabinet.ui.CabinetViewModel
import app.medicinecabinet.ui.theme.CabinetTheme

class MainActivity : ComponentActivity() {
    // 每个应用实例使用自己的工厂，避免共享工厂保留旧的 Application。
    private val viewModel by viewModels<CabinetViewModel> { ViewModelProvider.AndroidViewModelFactory(application) }
    private var shoppingRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getBooleanExtra("openShopping", false)) shoppingRequest++
        setContent {
            val interfaceSize by viewModel.interfaceSize.collectAsStateWithLifecycle()
            CabinetTheme(interfaceSize = interfaceSize) { CabinetApp(viewModel, shoppingRequest) }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("openShopping", false)) shoppingRequest++
    }
}
