package com.cinefx.videoeditor

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinefx.videoeditor.ui.theme.MyApplicationTheme
import com.cinefx.videoeditor.ui.theme.NeonCyan
import com.cinefx.videoeditor.ui.theme.SpaceObsidian
import com.cinefx.videoeditor.ui.theme.TextMuted
import kotlinx.coroutines.delay

@SuppressLint("CustomSplashScreen")
class SplashScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTheme {
                SplashContent {
                    startActivity(Intent(this@SplashScreenActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
    }
}

@Composable
private fun SplashContent(onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.8f) }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
        delay(1200)
        onFinished()
    }

    LaunchedEffect(Unit) {
        scale.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceObsidian),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .alpha(alpha.value)
                .scale(scale.value),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "CINEFX",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = NeonCyan,
                letterSpacing = 8.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Advanced Mobile Video Editor",
                fontSize = 14.sp,
                color = TextMuted,
                letterSpacing = 2.sp
            )
        }
    }
}
