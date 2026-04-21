package com.sam.btagent

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sam.btagent.databinding.FragmentLogViewerBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogViewerFragment : Fragment() {

    private var _binding: FragmentLogViewerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.rvLogFiles.layoutManager = LinearLayoutManager(context)
        refreshLogList()

        binding.btnRefreshLogs.setOnClickListener { refreshLogList() }
        binding.btnOpenDir.setOnClickListener { openLogDirectory() }
    }

    private fun refreshLogList() {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appLogDir = File(downloadDir, "BT_Android_Agent_Logs")
        
        if (!appLogDir.exists()) {
            appLogDir.mkdirs()
        }

        val files = appLogDir.listFiles { file -> file.isFile && file.extension == "csv" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        binding.rvLogFiles.adapter = LogAdapter(files)
    }

    private fun openLogDirectory() {
        Toast.makeText(context, "Logs located in: Downloads/BT_Android_Agent_Logs", Toast.LENGTH_LONG).show()
    }

    private inner class LogAdapter(private val files: List<File>) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val fileName: TextView = view.findViewById(R.id.tvLogFileName)
            val fileInfo: TextView = view.findViewById(R.id.tvLogFileInfo)
            val icon: ImageView = view.findViewById(R.id.ivLogIcon)
            val shareBtn: View = view.findViewById(R.id.btnShareLog)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            holder.fileName.text = file.name
            
            val size = file.length() / 1024
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
            holder.fileInfo.text = "Size: ${size}KB | $date"

            if (file.name.contains("Snapshot_ERROR") || file.name.contains("CrashLog")) {
                holder.icon.setImageResource(android.R.drawable.ic_dialog_alert)
                holder.icon.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            } else {
                holder.icon.setImageResource(android.R.drawable.ic_menu_agenda)
                holder.icon.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark))
            }

            holder.itemView.setOnClickListener {
                showPreview(file)
            }
            holder.itemView.setOnLongClickListener {
                showDeleteDialog(file)
                true
            }
            holder.shareBtn.setOnClickListener {
                shareFile(file)
            }
        }

        private fun showDeleteDialog(file: File) {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete Log?")
                .setMessage("Are you sure you want to delete ${file.name}?")
                .setPositiveButton("Delete") { _, _ ->
                    if (file.delete()) {
                        Toast.makeText(context, "File deleted", Toast.LENGTH_SHORT).show()
                        refreshLogList()
                    } else {
                        Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun showPreview(file: File) {
            val context = requireContext()
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_log_preview, null)
            val tvTitle = dialogView.findViewById<TextView>(R.id.tvPreviewTitle)
            val tvContent = dialogView.findViewById<TextView>(R.id.tvLogPreviewContent)

            tvTitle.text = "Preview: ${file.name} (Latest 200 lines)"
            
            try {
                // 優化：改用串流讀取，避免一次性將大檔案讀入記憶體 (P2 Review Fix)
                val lastLines = mutableListOf<String>()
                file.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        lastLines.add(line)
                        if (lastLines.size > 200) {
                            lastLines.removeAt(0)
                        }
                    }
                }
                tvContent.text = if (lastLines.isEmpty()) "(Empty file)" else lastLines.joinToString("\n")
            } catch (e: Exception) {
                tvContent.text = "Error reading file: ${e.message}"
            }

            AlertDialog.Builder(context)
                .setView(dialogView)
                .setPositiveButton("Share") { _, _ -> shareFile(file) }
                .setNegativeButton("Close", null)
                .show()
        }

        override fun getItemCount() = files.size
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Log File"))
        } catch (e: Exception) {
            Toast.makeText(context, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
