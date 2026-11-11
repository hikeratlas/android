package com.hikeratlas.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.hikeratlas.android.map.MapScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HikerAtlasApp
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2F6B3F), secondary = Color(0xFFC026D3))) {
                MapScreen(app.http)
            }
        }
    }
}
