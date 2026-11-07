package com.t30watch.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t30watch.ui.theme.T30Theme

/**
 * Health Connect opens this when you tap "privacy policy" on the permission screen. Android requires it
 * before an app may request health permissions.
 */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            T30Theme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("How T30 Watch uses health data", style = MaterialTheme.typography.headlineSmall)
                        Text("T30 Watch copies data from your T30 watch into Health Connect: steps, distance, active calories, heart rate, blood pressure, sleep and workouts.")
                        Text("It only writes. It doesn't read other apps' data.")
                        Text("Nothing is uploaded anywhere. The data stays on this phone, in the app and in Health Connect.")
                        Text("You can remove the permission at any time in Health Connect settings.")
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
