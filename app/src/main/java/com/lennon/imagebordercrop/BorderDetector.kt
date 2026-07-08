package com.lennon.imagebordercrop

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 边框检测结果
 */
data class BorderResult(
    val top: Int,       // 上边框像素宽度
    val bottom: Int,    // 下边框像素宽度
    val left: Int,      // 左边框像素宽度
    val right: Int,     // 右边框像素宽度
    val borderType: BorderType,
    val threshold: Int
) {
    /** 是否检测到任何边框 */
    fun hasBorder(): Boolean = top > 0 || bottom > 0 || left > 0 || right > 0

    fun summary(): String {
        return if (!hasBorder()) {
            "未检测到边框"
        } else {
            "上: ${top}px  下: ${bottom}px  左: ${left}px  右: ${right}px\n" +
            "边框类型: ${borderType.label}  阈值: $threshold"
        }
    }
}

/**
 * 边框类型
 */
enum class BorderType(val label: String) {
    BLACK("黑边"),
    WHITE("白边"),
    AUTO("自动检测");
}

/**
 * 图片边框检测器
 * 通过逐行/逐列扫描像素，检测连续的黑边或白边
 */
class BorderDetector {

    companion object {
        private const val LUMINANCE_BLACK_THRESHOLD = 30   // 亮度低于此值视为"黑"
        private const val LUMINANCE_WHITE_THRESHOLD = 225  // 亮度高于此值视为"白"
    }

    /**
     * 检测图片边框
     * @param bitmap 源图片
     * @param borderType 边框类型（黑/白/自动）
     * @param threshold 容差阈值 (0-255)，允许的颜色偏差范围
     * @return 边框检测结果
     */
    fun detect(bitmap: Bitmap, borderType: BorderType, threshold: Int): BorderResult {
        val width = bitmap.width
        val height = bitmap.height

        require(width > 0 && height > 0) { "图片尺寸不能为0" }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 自动检测边框颜色
        val actualType = if (borderType == BorderType.AUTO) {
            autoDetectBorderType(pixels, width, height)
        } else {
            borderType
        }

        val top = scanTopBorder(pixels, width, height, actualType, threshold)
        val bottom = scanBottomBorder(pixels, width, height, actualType, threshold)
        val left = scanLeftBorder(pixels, width, height, actualType, threshold)
        val right = scanRightBorder(pixels, width, height, actualType, threshold)

        return BorderResult(top, bottom, left, right, actualType, threshold)
    }

    /**
     * 裁剪边框
     * @param bitmap 源图片
     * @param result 边框检测结果
     * @return 裁剪后的图片
     */
    fun crop(bitmap: Bitmap, result: BorderResult): Bitmap {
        if (!result.hasBorder()) return bitmap

        val x = result.left
        val y = result.top
        val cropWidth = bitmap.width - result.left - result.right
        val cropHeight = bitmap.height - result.top - result.bottom

        require(cropWidth > 0 && cropHeight > 0) { "裁剪区域无效" }

        return Bitmap.createBitmap(bitmap, x, y, cropWidth, cropHeight)
    }

    /**
     * 自动检测四条边的主要颜色是偏黑还是偏白
     */
    private fun autoDetectBorderType(pixels: IntArray, width: Int, height: Int): BorderType {
        var totalLuminance = 0L
        var sampleCount = 0

        // 采样四条边缘区域（各取前10行/列）
        val sampleDepth = minOf(10, height / 2, width / 2)

        // 上边
        for (y in 0 until sampleDepth) {
            for (x in 0 until width) {
                totalLuminance += luminance(pixels[y * width + x])
                sampleCount++
            }
        }
        // 下边
        for (y in (height - sampleDepth) until height) {
            for (x in 0 until width) {
                totalLuminance += luminance(pixels[y * width + x])
                sampleCount++
            }
        }
        // 左边（排除已采样的上下角）
        for (y in sampleDepth until (height - sampleDepth)) {
            for (x in 0 until sampleDepth) {
                totalLuminance += luminance(pixels[y * width + x])
                sampleCount++
            }
        }
        // 右边
        for (y in sampleDepth until (height - sampleDepth)) {
            for (x in (width - sampleDepth) until width) {
                totalLuminance += luminance(pixels[y * width + x])
                sampleCount++
            }
        }

        val avgLuminance = if (sampleCount > 0) totalLuminance / sampleCount else 128
        return if (avgLuminance < 128) BorderType.BLACK else BorderType.WHITE
    }

    /**
     * 从上往下扫描，找到第一个非边框像素的行号
     */
    private fun scanTopBorder(
        pixels: IntArray, width: Int, height: Int,
        borderType: BorderType, threshold: Int
    ): Int {
        for (y in 0 until height) {
            if (!isBorderRow(pixels, width, y, borderType, threshold)) {
                return y
            }
        }
        return 0
    }

    /**
     * 从下往上扫描
     */
    private fun scanBottomBorder(
        pixels: IntArray, width: Int, height: Int,
        borderType: BorderType, threshold: Int
    ): Int {
        for (y in (height - 1) downTo 0) {
            if (!isBorderRow(pixels, width, y, borderType, threshold)) {
                return height - 1 - y
            }
        }
        return 0
    }

    /**
     * 从左往右扫描
     */
    private fun scanLeftBorder(
        pixels: IntArray, width: Int, height: Int,
        borderType: BorderType, threshold: Int
    ): Int {
        for (x in 0 until width) {
            if (!isBorderColumn(pixels, width, height, x, borderType, threshold)) {
                return x
            }
        }
        return 0
    }

    /**
     * 从右往左扫描
     */
    private fun scanRightBorder(
        pixels: IntArray, width: Int, height: Int,
        borderType: BorderType, threshold: Int
    ): Int {
        for (x in (width - 1) downTo 0) {
            if (!isBorderColumn(pixels, width, height, x, borderType, threshold)) {
                return width - 1 - x
            }
        }
        return 0
    }

    /**
     * 判断整行是否都属于边框
     */
    private fun isBorderRow(
        pixels: IntArray, width: Int, rowY: Int,
        borderType: BorderType, threshold: Int
    ): Boolean {
        val offset = rowY * width
        for (x in 0 until width) {
            if (!isBorderColor(pixels[offset + x], borderType, threshold)) {
                return false
            }
        }
        return true
    }

    /**
     * 判断整列是否都属于边框
     */
    private fun isBorderColumn(
        pixels: IntArray, width: Int, height: Int, colX: Int,
        borderType: BorderType, threshold: Int
    ): Boolean {
        for (y in 0 until height) {
            if (!isBorderColor(pixels[y * width + colX], borderType, threshold)) {
                return false
            }
        }
        return true
    }

    /**
     * 判断单个像素是否属于指定边框颜色
     */
    private fun isBorderColor(pixel: Int, borderType: BorderType, threshold: Int): Boolean {
        return when (borderType) {
            BorderType.BLACK -> isBlackPixel(pixel, threshold)
            BorderType.WHITE -> isWhitePixel(pixel, threshold)
            BorderType.AUTO -> isBlackPixel(pixel, threshold) || isWhitePixel(pixel, threshold)
        }
    }

    private fun isBlackPixel(pixel: Int, threshold: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        // 三个通道都足够暗
        return r <= threshold && g <= threshold && b <= threshold
    }

    private fun isWhitePixel(pixel: Int, threshold: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val min = minOf(r, g, b)
        // 三个通道都足够亮
        return min >= (255 - threshold)
    }

    private fun luminance(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
