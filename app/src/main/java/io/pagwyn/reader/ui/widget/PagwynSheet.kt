package io.pagwyn.reader.ui.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import io.pagwyn.reader.R

/**
 * Base class for Pagwyn's bottom sheets.
 *
 * Deliberately not a `BottomSheetDialogFragment`: Material's sheet brings in the
 * whole Material Components dependency (~600 KB) for a single interaction pattern.
 * This implementation is a plain dialog pinned to the bottom with the same
 * behaviour — drag handle, scrim, swipe-down dismissal, edge-to-edge width.
 */
abstract class PagwynSheet : androidx.fragment.app.DialogFragment() {

    protected lateinit var root: ViewGroup
        private set

    /** Height fraction of the available screen, 0..1. */
    protected open val heightFraction: Float = 0.7f

    protected abstract val layoutRes: Int

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), R.style.Pagwyn_SheetDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        root = inflater.inflate(layoutRes, container, false) as ViewGroup
        onViewCreated(root)
        return root
    }

    protected abstract fun onViewCreated(view: View)

    override fun onStart() {
        super.onStart()
        val dialog = dialog ?: return
        val window: Window = dialog.window ?: return
        val metrics = resources.displayMetrics
        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (metrics.heightPixels * heightFraction).toInt(),
        )
        window.setGravity(Gravity.BOTTOM)
        window.setDimAmount(0.35f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setBackgroundDrawableResource(R.drawable.bg_sheet)
        window.setWindowAnimations(R.style.Pagwyn_SheetAnimation)
        // Respect the navigation bar so the sheet content is never obscured.
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            val bottom = insets.systemWindowInsetBottom
            if (bottom > 0) {
                view.setPadding(0, 0, 0, bottom)
                insets
            } else {
                insets
            }
        }
    }
}
