// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * The one launcher activity (01 Application element and components): AppCompat for per-app language, the system
 * splash, edge-to-edge, then the shared Compose root.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent { } // NeutrodyneRoot arrives with the shared navigation host (01 M0 checklist step 15)
    }
}
