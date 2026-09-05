package com.receiptbox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.receiptbox.app.ui.navigation.ReceiptBoxNavHost
import com.receiptbox.app.ui.theme.ReceiptBoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ReceiptBoxApp
        setContent {
            ReceiptBoxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReceiptBoxNavHost(
                        receiptRepository = app.receiptRepository,
                        preferencesRepository = app.preferencesRepository,
                        billingManager = app.billingManager,
                        ocrHelper = app.ocrHelper
                    )
                }
            }
        }
    }
}
