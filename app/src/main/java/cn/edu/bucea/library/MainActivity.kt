package cn.edu.bucea.library

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.bucea.library.ui.LibraryApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val libraryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { mainViewModel.refresh() }
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
