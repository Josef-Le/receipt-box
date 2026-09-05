package com.receiptbox.app.ui.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.receiptbox.app.data.PreferencesRepository
import kotlinx.coroutines.launch

private data class Page(val title: String, val body: String, val icon: ImageVector)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    preferencesRepository: PreferencesRepository
) {
    val pages = listOf(
        Page(
            "Snap your receipt",
            "Camera or gallery. Photos and OCR stay on this device — no cloud.",
            Icons.Outlined.CameraAlt
        ),
        Page(
            "Structure everything",
            "We parse merchants, line items, taxes, discounts, and payments into a relational schema you can edit.",
            Icons.Outlined.EditNote
        ),
        Page(
            "Analytics & export",
            "Spend by store/product/period. Export CSV/JSON for your accountant. Pro unlocks full analytics.",
            Icons.Outlined.FileUpload
        )
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            val p = pages[page]
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    p.icon,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(32.dp))
                Text(p.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    p.body,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            repeat(pages.size) { i ->
                val selected = pagerState.currentPage == i
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(if (selected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        val last = pagerState.currentPage == pages.lastIndex
        Button(
            onClick = {
                if (last) {
                    scope.launch {
                        preferencesRepository.setOnboardingComplete(true)
                        onFinished()
                    }
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (last) "Get started" else "Next")
        }
        if (!last) {
            TextButton(onClick = {
                scope.launch {
                    preferencesRepository.setOnboardingComplete(true)
                    onFinished()
                }
            }) { Text("Skip") }
        }
    }
}
