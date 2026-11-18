package com.bartixxx.parked

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bartixxx.parked.ui.MainViewModel
import com.bartixxx.parked.ui.ParkedRoot
import com.bartixxx.parked.ui.theme.ParkedTheme

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
