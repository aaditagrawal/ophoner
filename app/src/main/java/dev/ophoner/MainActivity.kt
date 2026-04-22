package dev.ophoner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.ophoner.ui.navigation.OphoneNavGraph
import dev.ophoner.ui.theme.OphoneTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OphoneTheme {
                val navController = rememberNavController()
                OphoneNavGraph(navController)
            }
        }
    }
}
