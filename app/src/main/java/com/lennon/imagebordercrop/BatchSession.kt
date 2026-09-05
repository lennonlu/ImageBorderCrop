package com.lennon.imagebordercrop

/**
 * 仅记录批量处理的顺序和结果统计；图片本身仍由 MainActivity 逐张加载。
 */
internal class BatchSession<T>(items: List<T>) {
    private val items = items.toList()

    var currentIndex: Int = 0
        private set

    var savedCount: Int = 0
        private set

    var skippedCount: Int = 0
        private set

    init {
        require(this.items.size in 2..MAX_ITEMS) {
            "Batch size must be between 2 and $MAX_ITEMS"
        }
    }

    val total: Int get() = items.size
    val position: Int get() = currentIndex + 1
    val currentItem: T get() = items[currentIndex]
    val isLast: Boolean get() = currentIndex == items.lastIndex

    fun saveAndAdvance(): T? {
        savedCount++
        return advance()
    }

    fun skipAndAdvance(): T? {
        check(!isLast) { "The last item cannot be skipped from the batch action bar" }
        skippedCount++
        return advance()
    }

    private fun advance(): T? {
        if (isLast) return null
        currentIndex++
        return currentItem
    }

    companion object {
        const val MAX_ITEMS = 20
    }
}
