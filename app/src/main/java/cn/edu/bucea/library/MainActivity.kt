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
    private val mainViewModel: MainViewModel by viewModels()
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
            val features = featureViewModel
            val maps: NativeMapViewModel = viewModel()
            val nativeAccount: NativeAccountViewModel = viewModel()
            val verification: NativeVerificationViewModel = viewModel()
            val regions: RegionOverviewViewModel = viewModel()
            LibraryApp(
                viewModel = mainViewModel,
                features = features,
                regions = regions,
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
        mainViewModel.onForeground()
        featureViewModel.onForeground()
        updateViewModel.coordinator.onForeground()
    }

    override fun onPause() {
        updateViewModel.coordinator.onBackground()
        super.onPause()
    }
}
