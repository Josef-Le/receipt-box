package com.receiptbox.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.receiptbox.app.billing.BillingManager
import com.receiptbox.app.data.PreferencesRepository
import com.receiptbox.app.data.ReceiptRepository
import com.receiptbox.app.ocr.OcrHelper
import com.receiptbox.app.ui.analytics.AnalyticsScreen
import com.receiptbox.app.ui.analytics.AnalyticsViewModel
import com.receiptbox.app.ui.capture.CaptureScreen
import com.receiptbox.app.ui.capture.CaptureViewModel
import com.receiptbox.app.ui.detail.DetailScreen
import com.receiptbox.app.ui.detail.DetailViewModel
import com.receiptbox.app.ui.export.ExportScreen
import com.receiptbox.app.ui.export.ExportViewModel
import com.receiptbox.app.ui.home.HomeScreen
import com.receiptbox.app.ui.home.HomeViewModel
import com.receiptbox.app.ui.onboarding.OnboardingScreen
import com.receiptbox.app.ui.paywall.PaywallScreen
import com.receiptbox.app.ui.settings.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CAPTURE = "capture?uri={uri}"
    const val DETAIL = "detail/{id}"
    const val EXPORT = "export"
    const val ANALYTICS = "analytics"
    const val PAYWALL = "paywall"
    const val SETTINGS = "settings"

    fun capture(uri: String? = null) =
        if (uri != null) "capture?uri=${android.net.Uri.encode(uri)}" else "capture"

    fun detail(id: Long) = "detail/$id"
}

@Composable
fun ReceiptBoxNavHost(
    receiptRepository: ReceiptRepository,
    preferencesRepository: PreferencesRepository,
    billingManager: BillingManager,
    ocrHelper: OcrHelper
) {
    val navController = rememberNavController()
    val prefs by preferencesRepository.preferences.collectAsState(
        initial = com.receiptbox.app.data.AppPreferences()
    )
    val start = if (prefs.onboardingComplete) Routes.HOME else Routes.ONBOARDING

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                preferencesRepository = preferencesRepository
            )
        }
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(
                factory = HomeViewModel.factory(receiptRepository, preferencesRepository)
            )
            HomeScreen(
                viewModel = vm,
                onCapture = { navController.navigate(Routes.capture()) },
                onOpen = { id -> navController.navigate(Routes.detail(id)) },
                onExport = { navController.navigate(Routes.EXPORT) },
                onAnalytics = { navController.navigate(Routes.ANALYTICS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onPaywall = { navController.navigate(Routes.PAYWALL) }
            )
        }
        composable(
            route = "capture?uri={uri}",
            arguments = listOf(
                navArgument("uri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val initialUri = entry.arguments?.getString("uri")
            val vm: CaptureViewModel = viewModel(
                factory = CaptureViewModel.factory(receiptRepository, preferencesRepository, ocrHelper)
            )
            CaptureScreen(
                viewModel = vm,
                initialUri = initialUri,
                onSaved = { id ->
                    navController.navigate(Routes.detail(id)) { popUpTo(Routes.HOME) }
                },
                onPaywall = { navController.navigate(Routes.PAYWALL) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val vm: DetailViewModel = viewModel(factory = DetailViewModel.factory(id, receiptRepository))
            DetailScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onDeleted = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.EXPORT) {
            val vm: ExportViewModel = viewModel(
                factory = ExportViewModel.factory(receiptRepository, preferencesRepository)
            )
            ExportScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onPaywall = { navController.navigate(Routes.PAYWALL) }
            )
        }
        composable(Routes.ANALYTICS) {
            val vm: AnalyticsViewModel = viewModel(
                factory = AnalyticsViewModel.factory(receiptRepository, preferencesRepository)
            )
            AnalyticsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onPaywall = { navController.navigate(Routes.PAYWALL) }
            )
        }
        composable(Routes.PAYWALL) {
            PaywallScreen(
                billingManager = billingManager,
                preferencesRepository = preferencesRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                billingManager = billingManager,
                preferencesRepository = preferencesRepository,
                onBack = { navController.popBackStack() },
                onPaywall = { navController.navigate(Routes.PAYWALL) }
            )
        }
    }
}
