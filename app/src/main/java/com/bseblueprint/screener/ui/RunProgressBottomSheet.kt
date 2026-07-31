package com.bseblueprint.screener.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.bseblueprint.screener.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RunProgressBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onRunDismissed()
    }

    private var stageView: TextView? = null
    private var percentView: TextView? = null
    private var progressBar: LinearProgressIndicator? = null
    private var logView: TextView? = null
    private var logScroll: NestedScrollView? = null
    private var doneButton: MaterialButton? = null
    private var running = true
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    var listener: Listener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_run_progress, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isCancelable = false
        stageView = view.findViewById(R.id.runStage)
        percentView = view.findViewById(R.id.runPercent)
        progressBar = view.findViewById(R.id.runProgressBar)
        logView = view.findViewById(R.id.runLog)
        logScroll = view.findViewById(R.id.runLogScroll)
        doneButton = view.findViewById(R.id.runDoneButton)
        doneButton?.setOnClickListener {
            listener?.onRunDismissed()
            dismissAllowingStateLoss()
        }
        appendLog(getString(R.string.run_progress_starting))
    }

    fun updateProgress(percent: Int, message: String) {
        if (!isAdded) return
        stageView?.text = message
        percentView?.text = getString(R.string.run_progress_percent, percent)
        progressBar?.isIndeterminate = false
        progressBar?.progress = percent
        appendLog(message)
    }

    fun markComplete(success: Boolean, summary: String) {
        if (!isAdded) return
        running = false
        isCancelable = true
        progressBar?.progress = 100
        percentView?.text = getString(R.string.run_progress_percent, 100)
        stageView?.text = summary
        appendLog(summary)
        doneButton?.visibility = View.VISIBLE
        doneButton?.text = if (success) {
            getString(R.string.run_progress_done)
        } else {
            getString(R.string.run_progress_close)
        }
    }

    private fun appendLog(line: String) {
        val stamp = timeFmt.format(Date())
        val current = logView?.text?.toString().orEmpty()
        val next = if (current.isEmpty()) {
            "[$stamp] $line"
        } else {
            "$current\n[$stamp] $line"
        }
        logView?.text = next
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        const val TAG = "RunProgressBottomSheet"

        fun newInstance(): RunProgressBottomSheet = RunProgressBottomSheet()
    }
}
