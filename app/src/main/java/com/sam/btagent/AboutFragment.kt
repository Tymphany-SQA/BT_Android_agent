package com.sam.btagent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val versionName = try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pInfo.versionName
        } catch (e: Exception) {
            "0.00.07"
        }

        binding.aboutVersionText.text = "Version: v$versionName"
        binding.aboutDescriptionText.text = "BT Android Agent 2 is an internal validation tool designed for testing Bluetooth speaker stability, connection reliability, audio latency, and battery performance.\n\nDeveloped by TYMPHANY SQA."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
