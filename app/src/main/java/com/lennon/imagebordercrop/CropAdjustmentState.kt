package com.lennon.imagebordercrop

internal enum class CropSide {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT
}

internal data class CropInsets(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int
)

/** 保证手动调整后至少保留 1×1 像素的裁剪状态。 */
internal class CropAdjustmentState(
    private val width: Int,
    private val height: Int,
    initial: BorderResult,
    automatic: BorderResult = initial
) {
    private val automaticInsets = CropInsets(
        automatic.top,
        automatic.bottom,
        automatic.left,
        automatic.right
    )

    var insets: CropInsets = CropInsets(initial.top, initial.bottom, initial.left, initial.right)
        private set

    init {
        require(width > 0 && height > 0) { "图片尺寸无效" }
        require(initial.left + initial.right < width && initial.top + initial.bottom < height) {
            "初始裁剪区域无效"
        }
        require(
            automatic.left + automatic.right < width &&
                automatic.top + automatic.bottom < height
        ) { "自动检测裁剪区域无效" }
    }

    fun value(side: CropSide): Int = when (side) {
        CropSide.TOP -> insets.top
        CropSide.BOTTOM -> insets.bottom
        CropSide.LEFT -> insets.left
        CropSide.RIGHT -> insets.right
    }

    fun maximum(side: CropSide): Int = when (side) {
        CropSide.TOP -> height - insets.bottom - 1
        CropSide.BOTTOM -> height - insets.top - 1
        CropSide.LEFT -> width - insets.right - 1
        CropSide.RIGHT -> width - insets.left - 1
    }.coerceAtLeast(0)

    /** 滑块使用固定量程，避免调整一侧时相对侧滑块因量程变化而产生视觉位移。 */
    fun rangeMaximum(side: CropSide): Int = when (side) {
        CropSide.TOP, CropSide.BOTTOM -> height - 1
        CropSide.LEFT, CropSide.RIGHT -> width - 1
    }

    fun update(side: CropSide, requestedValue: Int) {
        val value = requestedValue.coerceIn(0, maximum(side))
        insets = when (side) {
            CropSide.TOP -> insets.copy(top = value)
            CropSide.BOTTOM -> insets.copy(bottom = value)
            CropSide.LEFT -> insets.copy(left = value)
            CropSide.RIGHT -> insets.copy(right = value)
        }
    }

    fun resetToAutomatic() {
        insets = automaticInsets
    }

    fun toResult(base: BorderResult): BorderResult = base.copy(
        top = insets.top,
        bottom = insets.bottom,
        left = insets.left,
        right = insets.right,
        manuallyAdjusted = insets != automaticInsets
    )
}
