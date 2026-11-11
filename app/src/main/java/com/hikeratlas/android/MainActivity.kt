package com.hikeratlas.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hikeratlas.android.ui.AppScreen
import com.hikeratlas.android.ui.HikerAtlasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HikerAtlasApp
        setContent {
            HikerAtlasTheme {
                AppScreen(app)
            }
        }
    }
}
