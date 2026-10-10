package com.peakform.fitness

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Blank host activity used by the Roborazzi screenshot harness (JVM-rendered captures).
 * It is registered in the manifest but is never exported and has no UI of its own;
 * it exists so the verification battery can render screens without launching the
 * real app shell (which gates content behind onboarding).
 */
class EmptyTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
