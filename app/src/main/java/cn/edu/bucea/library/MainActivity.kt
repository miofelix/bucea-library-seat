package cn.edu.bucea.library

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import cn.edu.bucea.library.ui.LibraryApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val libraryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                mainViewModel.onWebReturned(result.data?.getBooleanExtra("signed_out", false) == true)
            }
            LibraryApp(
                viewModel = mainViewModel,
                onOpenSeats = {
                    libraryLauncher.launch(
                        Intent(this, SeatWebActivity::class.java)
                            .putExtra("account_id", mainViewModel.state.value.activeAccountId)
                    )
                }
            )
        }
    }
}
