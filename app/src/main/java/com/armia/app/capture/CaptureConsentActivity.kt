package com.armia.app.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Activity invisível: abre o pedido de captura de tela do Android e entrega o resultado ao
 * [CaptureLabService]. No Android 14+ o sistema deixa escolher "um app" ou "a tela inteira".
 * Escolher só o jogo é o melhor: a captura não inclui o nosso filtro (sem efeito espelho).
 */
class CaptureConsentActivity : ComponentActivity() {

    private var targetPackage = ""
    private var fullscreen = false

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    CaptureLabService.startIntent(this, result.resultCode, data, targetPackage, fullscreen)
                )
            } else {
                Toast.makeText(this, "Captura não autorizada.", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        fullscreen = intent.getBooleanExtra(EXTRA_FULLSCREEN, false)
        if (savedInstanceState == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(manager.createScreenCaptureIntent())
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_FULLSCREEN = "fullscreen"

        fun intent(context: Context, packageName: String, fullscreen: Boolean): Intent =
            Intent(context, CaptureConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_FULLSCREEN, fullscreen)
    }
}
