package rs.zylos.novisad.ui.sheet

import android.content.Context
import android.util.AttributeSet
import androidx.coordinatorlayout.widget.CoordinatorLayout

/**
 * Content-zone host for the object sheet. Must call through to CoordinatorLayout
 * touch handling so BottomSheetBehavior can drag; empty space still falls through
 * to the map because this view is not clickable.
 */
class PassThroughCoordinatorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CoordinatorLayout(context, attrs, defStyleAttr)
