package com.vayunmathur.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.launcher.ui.LauncherScreen
import com.vayunmathur.library.ui.DynamicTheme

class MainActivity : ComponentActivity() {
    private var widgetHost: AppWidgetHost? = null
    private lateinit var bindWidgetLauncher: ActivityResultLauncher<Intent>
    private lateinit var configureWidgetLauncher: ActivityResultLauncher<Intent>

    var pendingWidgetId = mutableIntStateOf(-1)
    var pendingWidgetInfo = mutableStateOf<AppWidgetProviderInfo?>(null)
    var onWidgetBound: ((Int, AppWidgetProviderInfo) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        widgetHost = AppWidgetHost(this, 1024)

        bindWidgetLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val wId = pendingWidgetId.intValue
            val info = pendingWidgetInfo.value
            if (result.resultCode == RESULT_OK && wId != -1 && info != null) {
                val configIntent = info.configure
                if (configIntent != null) {
                    val configureIntent = Intent().apply {
                        component = configIntent
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wId)
                    }
                    configureWidgetLauncher.launch(configureIntent)
                } else {
                    onWidgetBound?.invoke(wId, info)
                }
            } else if (wId != -1) {
                widgetHost?.deleteAppWidgetId(wId)
            }
            clearPendingWidget()
        }

        configureWidgetLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val wId = pendingWidgetId.intValue
            val info = pendingWidgetInfo.value
            if (result.resultCode == RESULT_OK && wId != -1 && info != null) {
                onWidgetBound?.invoke(wId, info)
            } else if (wId != -1) {
                widgetHost?.deleteAppWidgetId(wId)
            }
            clearPendingWidget()
        }

        setContent {
            DynamicTheme {
                val viewModel: LauncherViewModel = viewModel()
                LauncherScreen(viewModel, widgetHost, this)
            }
        }
    }

    fun requestBindWidget(widgetId: Int, info: AppWidgetProviderInfo, onBound: (Int, AppWidgetProviderInfo) -> Unit) {
        pendingWidgetId.intValue = widgetId
        pendingWidgetInfo.value = info
        onWidgetBound = onBound

        val manager = AppWidgetManager.getInstance(this)
        val bound = manager.bindAppWidgetIdIfAllowed(widgetId, info.provider)
        if (bound) {
            val configIntent = info.configure
            if (configIntent != null) {
                val intent = Intent().apply {
                    component = configIntent
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
                configureWidgetLauncher.launch(intent)
            } else {
                onBound(widgetId, info)
                clearPendingWidget()
            }
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            }
            bindWidgetLauncher.launch(intent)
        }
    }

    private fun clearPendingWidget() {
        pendingWidgetId.intValue = -1
        pendingWidgetInfo.value = null
    }

    override fun onStart() {
        super.onStart()
        widgetHost?.startListening()
    }

    override fun onStop() {
        super.onStop()
        widgetHost?.stopListening()
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        // Launcher home — do nothing on back press
    }
}
