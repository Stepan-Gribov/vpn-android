package com.v2ray.ang.ui

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogcatBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.LogcatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogcatActivity : BaseActivity() {
    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }
    private val viewModel: LogcatViewModel by viewModels()
    private lateinit var adapter: LogcatRecyclerAdapter
    private lateinit var layoutManager: LinearLayoutManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_logcat))

        adapter = LogcatRecyclerAdapter(::onLogLongClick)
        layoutManager = LinearLayoutManager(this)
        binding.recyclerView.layoutManager = layoutManager
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        viewModel.updateAction.observe(this) { renderLogs() }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startTail()
    }

    override fun onPause() {
        viewModel.stopTail()
        super.onPause()
    }

    private fun renderLogs() {
        val wasAtBottom = adapter.itemCount == 0 ||
                layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 2
        adapter.submit(viewModel.visibleLines())
        if (wasAtBottom && adapter.itemCount > 0) {
            binding.recyclerView.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun onLogLongClick(log: String): Boolean {
        Utils.setClipboard(this, log)
        toastSuccess(R.string.toast_success)
        return true
    }

    private fun visibleLogText(): String =
        viewModel.visibleLines().joinToString("\n") { it.raw }

    private fun shareLogcat() {
        lifecycleScope.launch(Dispatchers.IO) {
            val logText = visibleLogText()

            val result = try {
                val shareDir = File(cacheDir, "shared_logs").apply {
                    mkdirs()
                }

                shareDir.listFiles()?.forEach { it.delete() }

                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val logFile = File(shareDir, "kindlyvpn_logs_$timestamp.txt")
                logFile.writeText(logText, Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    this@LogcatActivity,
                    "${packageName}.cache",
                    logFile
                )

                uri to logFile.name
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(e.localizedMessage ?: e.toString())
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, result.first)
                    putExtra(Intent.EXTRA_SUBJECT, result.second)
                    putExtra(Intent.EXTRA_TITLE, result.second)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(contentResolver, result.second, result.first)
                }

                startActivity(
                    Intent.createChooser(
                        shareIntent,
                        getString(R.string.logcat_share)
                    )
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_logcat, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.setKeyword(newText)
                    return false
                }
            })
            searchView.setOnCloseListener {
                viewModel.setKeyword("")
                false
            }
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.copy_all -> {
            Utils.setClipboard(this, visibleLogText())
            toastSuccess(R.string.toast_success)
            true
        }

        R.id.share_all -> {
            shareLogcat()
            true
        }

        R.id.clear_all -> {
            viewModel.clear()
            true
        }

        R.id.level_all, R.id.level_info, R.id.level_warn, R.id.level_error -> {
            item.isChecked = true
            viewModel.setMinPriority(
                when (item.itemId) {
                    R.id.level_info -> Log.INFO
                    R.id.level_warn -> Log.WARN
                    R.id.level_error -> Log.ERROR
                    else -> LogcatViewModel.PRIORITY_ALL
                },
            )
            true
        }

        else -> super.onOptionsItemSelected(item)
    }
}
