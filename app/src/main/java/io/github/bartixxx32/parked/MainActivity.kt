package io.github.bartixxx32.parked

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bartixxx32.parked.ui.MainViewModel
import io.github.bartixxx32.parked.ui.ParkedRoot
import io.github.bartixxx32.parked.ui.theme.ParkedTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ParkedApp).container
        setContent {
            ParkedTheme {
                val mainViewModel: MainViewModel = viewModel(
                    factory = MainViewModel.factory(container),
                )
                ParkedRoot(viewModel = mainViewModel)
            }
        }
    }
}
