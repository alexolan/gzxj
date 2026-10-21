package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.lut.creator.AiCompositionAdvice
import com.hinnka.mycamera.ui.icons.AppIcons
import com.hinnka.mycamera.ui.theme.AccentColor

/** 取景器 AI 构图助手的 UI 状态 */
sealed interface AiAdviceUiState {
    data object Idle : AiAdviceUiState
    data object Loading : AiAdviceUiState
    data class Success(val advice: AiCompositionAdvice) : AiAdviceUiState
    data class Error(
        val message: String?,
        val notConfigured: Boolean
    ) : AiAdviceUiState
}

@Composable
private fun rememberInteractionSource(): MutableInteractionSource =
    androidx.compose.runtime.remember { MutableInteractionSource() }

/**
 * AI 构图建议覆盖层
 *
 * Loading 显示顶部小胶囊，不打断取景；
 * Success / Error 显示半透明遮罩 + 卡片，点按遮罩任意处关闭。
 */
@Composable
fun AiAdviceOverlay(
    state: AiAdviceUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        AiAdviceUiState.Idle -> Unit
        AiAdviceUiState.Loading -> {
            // 不加 clickable：加载中不拦截点击对焦等取景手势，也不允许误取消
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    color = Color(0xCC1C1C1E),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(top = 56.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = AccentColor,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ai_composition_analyzing),
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
        is AiAdviceUiState.Success -> {
            // 卡片改垂直居中：底部锚定时下半截会被参数条/快门区盖住且无法滚动。
            // 居中 + 高度封顶（可用高度的一半）+ 卡内竖向滚动，长建议也能完整看到。
            BoxWithConstraints(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color(0x88000000))
                    .clickable(
                        indication = null,
                        interactionSource = rememberInteractionSource()
                    ) { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                CompositionAdviceCard(
                    advice = state.advice,
                    onDismiss = onDismiss,
                    onRetry = onRetry,
                    maxCardHeight = maxHeight * 0.52f,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .widthIn(max = 420.dp)
                )
            }
        }
        is AiAdviceUiState.Error -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color(0x88000000))
                    .clickable(
                        indication = null,
                        interactionSource = rememberInteractionSource()
                    ) { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                CompositionErrorCard(
                    message = state.message,
                    notConfigured = state.notConfigured,
                    onDismiss = onDismiss,
                    onRetry = onRetry,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 420.dp)
                )
            }
        }
    }
}

@Composable
private fun CompositionAdviceCard(
    advice: AiCompositionAdvice,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    maxCardHeight: Dp = Dp.Unspecified
) {
    Surface(
        color = Color(0xE61C1C1E),
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .heightIn(max = maxCardHeight)
            .clickable(
                indication = null,
                interactionSource = rememberInteractionSource()
            ) { }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = AppIcons.AutoAwesome,
                    contentDescription = null,
                    tint = AccentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.ai_composition_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.weight(1f))
                ScoreBadge(score = advice.score)
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.ai_composition_close),
                        tint = Color(0xB3FFFFFF),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (advice.headline.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = advice.headline,
                    fontSize = 13.sp,
                    color = Color(0xE6FFFFFF)
                )
            }
            Spacer(Modifier.height(8.dp))
            advice.tips.forEachIndexed { index, tip ->
                TipRow(index = index, title = tip.title, detail = tip.detail)
                if (index != advice.tips.lastIndex) {
                    Spacer(Modifier.height(7.dp))
                }
            }
            if (advice.highlight.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Row {
                    Text(
                        text = "★ ",
                        fontSize = 12.sp,
                        color = AccentColor
                    )
                    Text(
                        text = stringResource(R.string.ai_composition_highlight_label, advice.highlight),
                        fontSize = 12.sp,
                        color = Color(0xB3FFFFFF)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.ai_composition_retry),
                fontSize = 12.sp,
                color = AccentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable(
                        indication = null,
                        interactionSource = rememberInteractionSource()
                    ) { onRetry() }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun TipRow(index: Int, title: String, detail: String) {
    Row {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(18.dp)
                .background(AccentColor.copy(alpha = 0.22f), CircleShape)
        ) {
            Text(
                text = "${index + 1}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AccentColor
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            if (detail.isNotBlank()) {
                if (title.isNotBlank()) {
                    Spacer(Modifier.height(1.dp))
                }
                Text(
                    text = detail,
                    fontSize = 12.sp,
                    color = Color(0xCCFFFFFF),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun ScoreBadge(score: Int) {
    val color = when {
        score >= 80 -> Color(0xFF6FCF6F)
        score >= 60 -> AccentColor
        else -> Color(0xFFFFB74D)
    }
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = "$score",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun CompositionErrorCard(
    message: String?,
    notConfigured: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xE61C1C1E),
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = rememberInteractionSource()
            ) { }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_composition_error),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF8A80)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (notConfigured) {
                    stringResource(R.string.ai_composition_not_configured)
                } else {
                    message ?: stringResource(R.string.ai_composition_error_unknown)
                },
                fontSize = 12.sp,
                color = Color(0xCCFFFFFF),
                lineHeight = 17.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.ai_composition_close),
                    fontSize = 13.sp,
                    color = Color(0xB3FFFFFF),
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = rememberInteractionSource()
                        ) { onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Spacer(Modifier.width(12.dp))
                if (notConfigured) {
                    Text(
                        text = stringResource(R.string.ai_composition_open_settings),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentColor,
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = rememberInteractionSource()
                            ) { onOpenSettings() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.ai_composition_retry),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentColor,
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = rememberInteractionSource()
                            ) { onRetry() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
