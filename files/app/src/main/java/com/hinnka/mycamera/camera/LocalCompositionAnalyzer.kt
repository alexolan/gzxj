package com.hinnka.mycamera.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.hinnka.mycamera.R
import com.hinnka.mycamera.lut.creator.AiCompositionAdvice
import com.hinnka.mycamera.lut.creator.AiCompositionTip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 离线构图分析器：不依赖任何云端 API，直接在设备上分析预览帧。
 *
 * 背景：云端 AI 构图（OpenAIApiClient）需要用户自行配置 API Key，开箱即用
 * 场景下会抛 [com.hinnka.mycamera.lut.creator.AiServiceNotConfiguredException]。
 * 本分析器作为该场景下的兜底：基于经典摄影规则的纯 Kotlin 图像分析，
 * 输出与云端一致的 [AiCompositionAdvice] 结构。
 *
 * 算法概览（全部在降采样到 ~160px 的小图上进行，单次分析 < 100ms）：
 * 1. 亮度/饱和度网格 + Sobel 边缘能量图
 * 2. 主体估计：边缘能量与饱和度加权的质心
 * 3. 规则检查：三分法、对称性、地平线倾斜、曝光分布、边缘杂乱、主体贴边
 * 4. 加权评分 + 按严重度排序的建议
 */
object LocalCompositionAnalyzer {

    private const val ANALYSIS_WIDTH = 160
    private const val LUMA_MIN = 0.299f
    private const val LUMA_MAX_R = 0.587f
    private const val LUMA_MAX_G = 0.114f

    private data class CandidateTip(
        val weight: Int,
        val tip: AiCompositionTip
    )

    suspend fun analyze(bitmap: Bitmap, context: Context): AiCompositionAdvice =
        withContext(Dispatchers.Default) {
            val width: Int
            val height: Int
            val pixels: IntArray
            if (bitmap.width <= ANALYSIS_WIDTH) {
                width = bitmap.width
                height = bitmap.height
                pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            } else {
                val scaled = bitmap.scale(ANALYSIS_WIDTH, max(1, bitmap.height * ANALYSIS_WIDTH / bitmap.width))
                width = scaled.width
                height = scaled.height
                pixels = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                if (scaled !== bitmap) scaled.recycle()
            }
            if (width < 8 || height < 8) {
                return@withContext fallbackAdvice(context)
            }
            analyzePixels(pixels, width, height, context)
        }

    private fun analyzePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        context: Context
    ): AiCompositionAdvice {
        // --- 1. 亮度/饱和度场 ---
        val luma = FloatArray(pixels.size)
        val saturation = FloatArray(pixels.size)
        var lumaSum = 0f
        var blownCount = 0
        var crushedCount = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val l = LUMA_MIN * r + LUMA_MAX_R * g + LUMA_MAX_G * b
            luma[i] = l
            lumaSum += l
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            saturation[i] = if (mx > 0f) (mx - mn) / mx else 0f
            if (l > 0.98f) blownCount++
            if (l < 0.02f) crushedCount++
        }
        val pixelCount = pixels.size.toFloat()
        val meanLuma = lumaSum / pixelCount
        val blownRatio = blownCount / pixelCount
        val crushedRatio = crushedCount / pixelCount

        // --- 2. Sobel 边缘能量 ---
        val edge = FloatArray(pixelCount.toInt())
        var edgeTotal = 0f
        var borderEdge = 0f
        val borderX = max(1, width / 12)
        val borderY = max(1, height / 12)
        for (y in 1 until height - 1) {
            val row = y * width
            val inBorderY = y < borderY || y >= height - borderY
            for (x in 1 until width - 1) {
                val i = row + x
                val gx = abs(
                    -luma[i - width - 1] - 2f * luma[i - 1] - luma[i + width - 1] +
                        luma[i - width + 1] + 2f * luma[i + 1] + luma[i + width + 1]
                )
                val gy = abs(
                    -luma[i - width - 1] - 2f * luma[i - width] - luma[i - width + 1] +
                        luma[i + width - 1] + 2f * luma[i + width] + luma[i + width + 1]
                )
                val e = gx + gy
                edge[i] = e
                edgeTotal += e
                if (inBorderY || x < borderX || x >= width - borderX) borderEdge += e
            }
        }
        val borderEdgeRatio = if (edgeTotal > 0f) borderEdge / edgeTotal else 0f
        // 边缘带占画面约 (1-(1-2/12)^2)≈31%，超过 45% 视为杂乱
        val busyEdges = borderEdgeRatio > 0.45f && edgeTotal / pixelCount > 0.04f

        // --- 3. 主体质心（边缘能量 + 饱和度加权）---
        var weightSum = 0f
        var cx = 0f
        var cy = 0f
        var maxWeight = 0f
        for (y in 1 until height - 1) {
            val row = y * width
            val rowY = y + 0.5f
            for (x in 1 until width - 1) {
                val i = row + x
                val w = (edge[i] + 0.01f) * (0.4f + saturation[i])
                if (w > 0f) {
                    weightSum += w
                    cx += w * (x + 0.5f)
                    cy += w * rowY
                }
                if (w > maxWeight) maxWeight = w
            }
        }
        if (weightSum <= 0f) return fallbackAdvice(context)
        val subjectX = cx / weightSum / width
        val subjectY = cy / weightSum / height

        // --- 4. 对称性（左右镜像差）---
        var mirrorDiffSum = 0f
        var mirrorCount = 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width / 2) {
                mirrorDiffSum += abs(luma[row + x] - luma[row + width - 1 - x])
                mirrorCount++
            }
        }
        val mirrorDiff = if (mirrorCount > 0) mirrorDiffSum / mirrorCount else 1f
        val symmetric = mirrorDiff < 0.06f && abs(subjectX - 0.5f) < 0.08f

        // --- 5. 地平线倾斜（左右两半的最大水平梯度行中位差）---
        val tiltDegrees = estimateHorizonTilt(luma, width, height)
        val horizonDetected = tiltDegrees != null
        val tilt = tiltDegrees ?: 0f

        // --- 6. 规则检查与打分 ---
        val tips = mutableListOf<CandidateTip>()
        var score = 62

        // 三分法：质心到最近三分交点的归一化距离
        val px = if (subjectX < 0.5f) 1f / 3f else 2f / 3f
        val py = if (subjectY < 0.5f) 1f / 3f else 2f / 3f
        val thirdsDist = hypot(subjectX - px, subjectY - py)
        var thirdsGood = false
        if (thirdsDist <= 0.13f) {
            score += 9
            thirdsGood = true
        } else if (!symmetric) {
            // 对称构图主体居中是合理的，不提示三分法
            if (thirdsDist >= 0.3f) {
                score -= 12
                tips += CandidateTip(
                    12,
                    AiCompositionTip(
                        title = context.getString(R.string.ai_composition_local_tip_thirds_title),
                        detail = context.getString(R.string.ai_composition_local_tip_thirds_detail)
                    )
                )
            } else {
                score -= 4
            }
        }

        // 主体贴边
        val borderMargin = 0.12f
        if (subjectX < borderMargin || subjectX > 1f - borderMargin ||
            subjectY < borderMargin || subjectY > 1f - borderMargin
        ) {
            score -= 8
            tips += CandidateTip(
                8,
                AiCompositionTip(
                    title = context.getString(R.string.ai_composition_local_tip_border_title),
                    detail = context.getString(R.string.ai_composition_local_tip_border_detail)
                )
            )
        }

        // 地平线
        if (horizonDetected && abs(tilt) >= 2f) {
            score -= if (abs(tilt) >= 5f) 12 else 7
            tips += CandidateTip(
                if (abs(tilt) >= 5f) 13 else 9,
                AiCompositionTip(
                    title = context.getString(R.string.ai_composition_local_tip_horizon_title),
                    detail = context.getString(
                        R.string.ai_composition_local_tip_horizon_detail,
                        "%.1f".format(abs(tilt))
                    )
                )
            )
        } else if (horizonDetected && abs(tilt) < 0.8f) {
            score += 5
        }

        // 对称性奖励
        if (symmetric) score += 7

        // 曝光
        var exposureBalanced = true
        if (blownRatio > 0.08f) {
            score -= 10
            exposureBalanced = false
            tips += CandidateTip(
                11,
                AiCompositionTip(
                    title = context.getString(R.string.ai_composition_local_tip_blown_title),
                    detail = context.getString(
                        R.string.ai_composition_local_tip_blown_detail,
                        (blownRatio * 100).toInt()
                    )
                )
            )
        }
        if (crushedRatio > 0.12f) {
            score -= 9
            exposureBalanced = false
            tips += CandidateTip(
                10,
                AiCompositionTip(
                    title = context.getString(R.string.ai_composition_local_tip_crushed_title),
                    detail = context.getString(
                        R.string.ai_composition_local_tip_crushed_detail,
                        (crushedRatio * 100).toInt()
                    )
                )
            )
        }
        if (exposureBalanced && blownRatio < 0.02f && crushedRatio < 0.05f) {
            score += 4
        }
        if (meanLuma < 0.22f) {
            score -= 5
            tips += CandidateTip(
                6,
                AiCompositionTip(
                    title = context.getString(R.string.ai_composition_local_tip_dark_title),
                    detail = context.getString(R.string.ai_composition_local_tip_dark_detail)
                )
            )
        } else if (meanLuma > 0.82f) {
            score -= 5
            tips += CandidateTip(
                6,
                AiCompositionTip(
                    title = context.getString(R.string.ai_composition_local_tip_bright_title),
                    detail = context.getString(R.string.ai_composition_local_tip_bright_detail)
                )
            )
        }

        // 边缘杂乱
        if (busyEdges) {
            score -= 6
            tips += CandidateTip(
                7,
                AiCompositionTip(
                    title = context.getString(R.string.ai_composition_local_tip_edge_title),
                    detail = context.getString(R.string.ai_composition_local_tip_edge_detail)
                )
            )
        }

        val finalScore = score.coerceIn(35, 96)

        // --- 7. 高亮点（做得最好的一项）---
        val highlightRes = when {
            thirdsGood -> R.string.ai_composition_local_highlight_thirds
            symmetric -> R.string.ai_composition_local_highlight_symmetry
            horizonDetected && abs(tilt) < 0.8f -> R.string.ai_composition_local_highlight_horizon
            else -> R.string.ai_composition_local_highlight_exposure
        }

        // --- 8. 标题 ---
        val headlineRes = when {
            finalScore >= 82 -> R.string.ai_composition_local_headline_excellent
            finalScore >= 66 -> R.string.ai_composition_local_headline_good
            finalScore >= 50 -> R.string.ai_composition_local_headline_fair
            else -> R.string.ai_composition_local_headline_poor
        }
        val badge = context.getString(R.string.ai_composition_local_badge)

        return AiCompositionAdvice(
            score = finalScore,
            headline = "${context.getString(headlineRes)} · $badge",
            tips = tips.sortedByDescending { it.weight }
                .take(3)
                .map { it.tip },
            highlight = context.getString(highlightRes)
        )
    }

    /**
     * 估计地平线倾角：对每列找最大垂直梯度行（能量超过该列峰值一半的行中位），
     * 比较左右两段列的中位行差。需要足够多的有效列才认定检测到地平线。
     */
    private fun estimateHorizonTilt(luma: FloatArray, width: Int, height: Int): Float? {
        if (height < 12) return null
        val half = width / 2
        val leftRows = mutableListOf<Float>()
        val rightRows = mutableListOf<Float>()

        fun scanColumns(from: Int, until: Int, out: MutableList<Float>) {
            for (x in from until until) {
                var bestGrad = 0f
                var bestRow = -1
                val rowSum = FloatArray(height)
                for (y in 1 until height - 1) {
                    val g = abs(luma[(y + 1) * width + x] - luma[(y - 1) * width + x])
                    rowSum[y] = g
                    if (g > bestGrad) {
                        bestGrad = g
                        bestRow = y
                    }
                }
                if (bestRow < 0) continue
                // 峰值明显才认为该列存在结构边缘
                val meanGrad = rowSum.sum() / height
                if (bestGrad > max(0.06f, meanGrad * 3f)) {
                    out.add(bestRow.toFloat())
                }
            }
        }

        scanColumns(1, half, leftRows)
        scanColumns(half, width - 1, rightRows)
        if (leftRows.size < half / 3 || rightRows.size < half / 3) return null

        fun median(v: MutableList<Float>): Float {
            if (v.isEmpty()) return Float.NaN
            v.sort()
            return v[v.size / 2]
        }

        val leftMedian = median(leftRows)
        val rightMedian = median(rightRows)
        if (leftMedian.isNaN() || rightMedian.isNaN()) return null
        val dRow = rightMedian - leftMedian
        val dCol = (half - 1 + half) / 2f // 两段中点间距近似
        if (dCol <= 0f) return null
        return Math.toDegrees(atan2(dRow, dCol).toDouble()).toFloat()
    }

    private fun fallbackAdvice(context: Context): AiCompositionAdvice {
        return AiCompositionAdvice(
            score = 60,
            headline = "${context.getString(R.string.ai_composition_local_headline_fair)} · " +
                context.getString(R.string.ai_composition_local_badge),
            tips = listOf(
                AiCompositionTip(
                    title = context.getString(R.string.ai_composition_local_tip_thirds_title),
                    detail = context.getString(R.string.ai_composition_local_tip_thirds_detail)
                )
            ),
            highlight = context.getString(R.string.ai_composition_local_highlight_exposure)
        )
    }
}
