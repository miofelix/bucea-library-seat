package cn.edu.bucea.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import cn.edu.bucea.library.ui.LibraryApp
import cn.edu.bucea.library.update.UpdateViewModel

class MainActivity : ComponentActivity() {
    private val featureViewModel: LibraryFeatureViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val features = featureViewModel
            val maps: NativeMapViewModel = viewModel()
            val nativeAccount: NativeAccountViewModel = viewModel()
            val verification: NativeVerificationViewModel = viewModel()
            LibraryApp(
                viewModel = mainViewModel,
                features = features,
                maps = maps,
                nativeAccount = nativeAccount,
                verification = verification,
                updates = updateViewModel,
                onInstallUpdate = { updateViewModel.install(this) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        featureViewModel.onForeground()
        updateViewModel.coordinator.onForeground()
    }

    override fun onPause() {
        updateViewModel.coordinator.onBackground()
        super.onPause()
    }
}
