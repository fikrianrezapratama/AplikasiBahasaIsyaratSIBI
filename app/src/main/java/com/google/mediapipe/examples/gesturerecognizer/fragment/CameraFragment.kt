package com.google.mediapipe.examples.gesturerecognizer.fragment

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageButton
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mediapipe.examples.gesturerecognizer.GestureRecognizerHelper
import com.google.mediapipe.examples.gesturerecognizer.MainViewModel
import com.google.mediapipe.examples.gesturerecognizer.R
import com.google.mediapipe.examples.gesturerecognizer.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(),
    GestureRecognizerHelper.GestureRecognizerListener {

    companion object {
        private const val TAG = "Hand gesture recognizer"
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var gestureRecognizerHelper: GestureRecognizerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var defaultNumResults = 1
    private val gestureRecognizerResultAdapter: GestureRecognizerResultsAdapter by lazy {
        GestureRecognizerResultsAdapter().apply {
            updateAdapterSize(defaultNumResults)
        }
    }
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    private var torchState: Boolean = false


    private lateinit var backgroundExecutor: ExecutorService

    private lateinit var sharedPreferences : SharedPreferences

    private var detection: Float = 0.5f
    private var tracking: Float = 0.5f
    private var preference: Float = 0.5f

    private var sibiValue: Boolean = false
    private var alfabetValue: Boolean = false
    private var angkaValue: Boolean = false
    private var kataValue: Boolean = false
    private var kalimatValue: Boolean = false
    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        // Start the GestureRecognizerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if (gestureRecognizerHelper.isClosed()) {
                gestureRecognizerHelper.setupGestureRecognizer()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::gestureRecognizerHelper.isInitialized) {
            viewModel.setMinHandDetectionConfidence(gestureRecognizerHelper.minHandDetectionConfidence)
            viewModel.setMinHandTrackingConfidence(gestureRecognizerHelper.minHandTrackingConfidence)
            viewModel.setMinHandPresenceConfidence(gestureRecognizerHelper.minHandPresenceConfidence)
            viewModel.setDelegate(gestureRecognizerHelper.currentDelegate)

            // Close the Gesture Recognizer helper and release resources
            backgroundExecutor.execute { gestureRecognizerHelper.clearGestureRecognizer() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        sharedPreferences = requireActivity().getSharedPreferences("pref", 0)
        sharedPreferences.edit().clear().apply()

        sibiValue = true
        alfabetValue = true

        saveBahasa("SIBI", sibiValue)
        saveBahasa("ALFABET", alfabetValue)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission", "ResourceType", "InflateParams")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(fragmentCameraBinding.recyclerviewResults) {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = gestureRecognizerResultAdapter
        }

        backgroundExecutor = Executors.newSingleThreadExecutor()

        fragmentCameraBinding.viewFinder.post {
            setUpCamera()
        }

        fragmentCameraBinding.penerjemahBtnFlipCamera.setOnClickListener {
            switchCamera()
        }

        fragmentCameraBinding.penerjemahBtnFlashCamera.setOnClickListener {
            turnOnFlash()
        }

        backgroundExecutor.execute {
            gestureRecognizerHelper = GestureRecognizerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                currentModel = viewModel.currentModel,
                gestureRecognizerListener = this
            )
        }

        fragmentCameraBinding.pilihanBahasaBtn.setOnClickListener {
            initBottomSheetBahasa()
        }

        fragmentCameraBinding.penerjemahBtnMenu.setOnClickListener {
            initBottomSheetControls()
        }
    }

    private fun initBottomSheetControls() {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.info_bottom_sheets)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setCancelable(false)

        val cancelBtn = dialog.findViewById<Button>(R.id.batalSettingBtn)
        val terapkanBtn = dialog.findViewById<Button>(R.id.terapkanSettingBtn)

        val detectionThresholdValue = dialog.findViewById<TextView>(R.id.detection_threshold_value)
        val trackingThresholdValue = dialog.findViewById<TextView>(R.id.tracking_threshold_value)
        val presenceThresholdValue = dialog.findViewById<TextView>(R.id.presence_threshold_value)

        val detectionThresholdMinus = dialog.findViewById<AppCompatImageButton>(R.id.detection_threshold_minus)
        val detectionThresholdPlus = dialog.findViewById<AppCompatImageButton>(R.id.detection_threshold_plus)
        val trackingThresholdMinus = dialog.findViewById<AppCompatImageButton>(R.id.tracking_threshold_minus)
        val trackingThresholdPlus = dialog.findViewById<AppCompatImageButton>(R.id.tracking_threshold_plus)
        val presenceThresholdMinus = dialog.findViewById<AppCompatImageButton>(R.id.presence_threshold_minus)
        val presenceThresholdPlus = dialog.findViewById<AppCompatImageButton>(R.id.presence_threshold_plus)

        val detectionValue = loadValueDetection()
        val trackingValue = loadValueTracking()
        val preferenceValue = loadValuePreference()

        detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", detectionValue
            )
        trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", trackingValue
            )
        presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", preferenceValue
            )

        detectionThresholdMinus.setOnClickListener {
            if (detection >= 0.2f) {
                detection -= 0.1f
                updateControlsUi(detectionThresholdValue, trackingThresholdValue, presenceThresholdValue)
            }
        }

        detectionThresholdPlus.setOnClickListener {
            if (detection <= 0.8) {
                detection += 0.1f
                updateControlsUi(detectionThresholdValue, trackingThresholdValue, presenceThresholdValue)
            }
        }

        trackingThresholdMinus.setOnClickListener {
            if (tracking >= 0.2) {
                tracking -= 0.1f
                updateControlsUi(detectionThresholdValue, trackingThresholdValue, presenceThresholdValue)
            }
        }

        trackingThresholdPlus.setOnClickListener {
            if (tracking <= 0.8) {
                tracking += 0.1f
                updateControlsUi(detectionThresholdValue, trackingThresholdValue, presenceThresholdValue)
            }
        }

        presenceThresholdMinus.setOnClickListener {
            if (preference >= 0.2) {
                preference -= 0.1f
                updateControlsUi(detectionThresholdValue, trackingThresholdValue, presenceThresholdValue)
            }
        }

        presenceThresholdPlus.setOnClickListener {
            if (preference <= 0.8) {
                preference += 0.1f
                updateControlsUi(detectionThresholdValue, trackingThresholdValue, presenceThresholdValue)
            }
        }

        cancelBtn.setOnClickListener {
            detection = detectionValue
            tracking = trackingValue
            preference = preferenceValue
            dialog.dismiss()
        }

        terapkanBtn.setOnClickListener {
            saveDetection(detection)
            saveTracking(tracking)
            savePreference(preference)

            gestureRecognizerHelper.minHandDetectionConfidence = detection
            gestureRecognizerHelper.minHandTrackingConfidence = tracking
            gestureRecognizerHelper.minHandPresenceConfidence = preference

            dialog.dismiss()
        }
        dialog.show()
    }

    private fun updateControlBahasa(){
        backgroundExecutor.execute {
            gestureRecognizerHelper.clearGestureRecognizer()
            gestureRecognizerHelper.setupGestureRecognizer()
        }
    }

    @SuppressLint("CommitPrefEdits", "InflateParams", "SetTextI18n")
    private fun initBottomSheetBahasa(){
        val dialogView = layoutInflater.inflate(R.layout.fragment_bottom_sheet_camera, null)
        val dialog = BottomSheetDialog(requireContext(),R.style.BottomSheetDialogTheme)
        dialog.setContentView(dialogView)

        val pilPenerjemahBtn = dialog.findViewById<RadioButton>(R.id.pilPenerjemahBtn)
        val bahasaBtn = dialog.findViewById<RadioButton>(R.id.bahasaBtn)
        val bahasaContainer = dialog.findViewById<RadioGroup>(R.id.radioGroupBahasa)
        val penerjemahContainer = dialog.findViewById<RadioGroup>(R.id.radioGroupPenerjemah)
        val underlineBahasa = dialog.findViewById<View>(R.id.underlineBahasa)
        val underlinePilihan = dialog.findViewById<View>(R.id.underlinePilihan)
        val terapkanBtn = dialog.findViewById<Button>(R.id.terapkanBtn)
        val batalBtn = dialog.findViewById<Button>(R.id.batalbtn)

        // Bahasa
        val sibi = dialog.findViewById<RadioButton>(R.id.sibi)
        val bisindo = dialog.findViewById<RadioButton>(R.id.bisindo)
        val asl = dialog.findViewById<RadioButton>(R.id.asl)

        // Penerjemah
        val alfabet = dialog.findViewById<RadioButton>(R.id.alfabet)
        val angka = dialog.findViewById<RadioButton>(R.id.angka)
        val kata = dialog.findViewById<RadioButton>(R.id.kata)
        val kalimat = dialog.findViewById<RadioButton>(R.id.kalimat)


        bahasaBtn?.setOnClickListener {
            pilPenerjemahBtn?.isChecked = false
            bahasaContainer?.visibility = View.VISIBLE
            underlineBahasa?.visibility = View.VISIBLE
            penerjemahContainer?.visibility = View.GONE
            underlinePilihan?.visibility = View.GONE
        }

        pilPenerjemahBtn?.setOnClickListener {
            bahasaBtn?.isChecked = false
            bahasaContainer?.visibility = View.GONE
            underlineBahasa?.visibility = View.GONE
            penerjemahContainer?.visibility = View.VISIBLE
            underlinePilihan?.visibility = View.VISIBLE
        }

        sibi?.isChecked = updateBahasa("SIBI")
        bisindo?.isChecked = updateBahasa("BISINDO")
        asl?.isChecked = updateBahasa("ASL")

        alfabet?.isChecked = updateBahasa("ALFABET")
        angka?.isChecked = updateBahasa("ANGKA")
        kata?.isChecked = updateBahasa("KATA")
        kalimat?.isChecked = updateBahasa("KALIMAT")

        sibi?.setOnCheckedChangeListener { _, b ->
            sibiValue = b
        }

        alfabet?.setOnCheckedChangeListener { _, b ->
            alfabetValue = b
        }

        angka?.setOnCheckedChangeListener { _, b ->
            angkaValue = b
        }

        kata?.setOnCheckedChangeListener { _, b ->
            kataValue = b
        }

        kalimat?.setOnCheckedChangeListener { _, b ->
            kalimatValue = b
        }

        terapkanBtn?.setOnClickListener {
            saveBahasa("SIBI", sibiValue)

            saveBahasa("ALFABET", alfabetValue)
            saveBahasa("ANGKA", angkaValue)
            saveBahasa("KATA", kataValue)
            saveBahasa("KALIMAT", kalimatValue)

            if (sibiValue){
                fragmentCameraBinding.pilihanBahasaBtn.text = "SIBI"
                if (alfabetValue){
                    Toast.makeText(context, "SIBI - ALFABET", Toast.LENGTH_SHORT).show()
                    gestureRecognizerHelper.currentModel = GestureRecognizerHelper.SIBI_ALFABET
                    updateControlBahasa()
                } else if (angkaValue){
                    Toast.makeText(context, "SIBI - ANGKA", Toast.LENGTH_SHORT).show()
                    gestureRecognizerHelper.currentModel = GestureRecognizerHelper.SIBI_ANGKA
                    updateControlBahasa()
                } else if (kataValue){
                    Toast.makeText(context, "SIBI - KATA", Toast.LENGTH_SHORT).show()
                } else if (kalimatValue){
                    Toast.makeText(context, "SIBI - KALIMAT", Toast.LENGTH_SHORT).show()
                    gestureRecognizerHelper.currentModel = GestureRecognizerHelper.SIBI_KALIMAT
                    updateControlBahasa()
                }
            }

            dialog.dismiss()
        }

        batalBtn?.setOnClickListener {
            sibi?.isChecked = updateBahasa("SIBI")
            bisindo?.isChecked = updateBahasa("BISINDO")
            asl?.isChecked = updateBahasa("ASL")

            alfabet?.isChecked = updateBahasa("ALFABET")
            angka?.isChecked = updateBahasa("ANGKA")
            kata?.isChecked = updateBahasa("KATA")
            kalimat?.isChecked = updateBahasa("KALIMAT")
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun updateControlsUi(detectionThresholdValue: TextView, trackingThresholdValue: TextView, presenceThresholdValue: TextView) {
        detectionThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                detection
            )
        trackingThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                tracking
            )
        presenceThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                preference
            )

        // Needs to be cleared instead of reinitialized because the GPU
        // delegate needs to be initialized on the thread using it when applicable
        backgroundExecutor.execute {
            gestureRecognizerHelper.clearGestureRecognizer()
            gestureRecognizerHelper.setupGestureRecognizer()
        }
        fragmentCameraBinding.overlay.clear()
    }

    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        recognizeHand(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun recognizeHand(imageProxy: ImageProxy) {
        gestureRecognizerHelper.recognizeLiveStream(
            imageProxy = imageProxy,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after a hand gesture has been recognized. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView. Only one result is expected at a time. If two or more
    // hands are seen in the camera frame, only one will be processed.
    override fun onResults(
        resultBundle: GestureRecognizerHelper.ResultBundle
    ) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                // Show result of recognized gesture
                val gestureCategories = resultBundle.results.first().gestures()
                if (gestureCategories.isNotEmpty()) {
                    gestureRecognizerResultAdapter.updateResults(
                        gestureCategories.first()
                    )
                }
                else {
                    gestureRecognizerResultAdapter.updateResults(emptyList())
                }

                if (cameraFacing == CameraSelector.LENS_FACING_FRONT){
                    fragmentCameraBinding.overlay.setResults(
                        resultBundle.results.first(),
                        resultBundle.inputImageHeight,
                        resultBundle.inputImageWidth,
                        RunningMode.LIVE_STREAM
                    )
                    fragmentCameraBinding.overlay.invalidate()
                }
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            gestureRecognizerResultAdapter.updateResults(emptyList())
        }
    }

    private fun loadValueDetection(): Float {
        sharedPreferences = requireActivity().getSharedPreferences("pref", Context.MODE_PRIVATE)
        return sharedPreferences.getFloat("DETECTION", 0.5F)
    }

    private fun loadValueTracking(): Float {
        sharedPreferences = requireActivity().getSharedPreferences("pref", Context.MODE_PRIVATE)
        return sharedPreferences.getFloat("TRACKING", 0.5F)
    }

    private fun loadValuePreference(): Float {
        sharedPreferences = requireActivity().getSharedPreferences("pref", Context.MODE_PRIVATE)
        return sharedPreferences.getFloat("PREFERENCE", 0.5F)
    }

    @SuppressLint("CommitPrefEdits")
    private fun saveDetection(value : Float){
        sharedPreferences = requireActivity().getSharedPreferences("pref", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putFloat("DETECTION", value)
        editor.apply()
    }

    @SuppressLint("CommitPrefEdits")
    private fun saveTracking(value : Float){
        sharedPreferences = requireActivity().getSharedPreferences("pref", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putFloat("TRACKING", value)
        editor.apply()
    }

    private fun savePreference(value : Float){
        sharedPreferences = requireActivity().getSharedPreferences("pref", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putFloat("PREFERENCE", value)
        editor.apply()
    }

    private fun saveBahasa(key: String, value: Boolean){
        sharedPreferences = requireActivity().getSharedPreferences("pref", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putBoolean(key, value)
        editor.apply()
    }

    private fun updateBahasa(key: String): Boolean {
        sharedPreferences = requireActivity().getSharedPreferences("pref", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean(key, false)
    }

    private fun switchCamera() {
        cameraFacing = if (CameraSelector.LENS_FACING_FRONT == cameraFacing) {
            fragmentCameraBinding.penerjemahBtnFlipCamera.setImageResource(R.drawable.ic_switch_camera)
            CameraSelector.LENS_FACING_BACK
        } else {
            fragmentCameraBinding.penerjemahBtnFlipCamera.setImageResource(R.drawable.ic_switch_camera)
            CameraSelector.LENS_FACING_FRONT
        }
        bindCameraUseCases()
    }



    private fun turnOnFlash() {
        fragmentCameraBinding.penerjemahBtnFlashCamera.setOnClickListener {
            torchState = when (torchState){
                false -> {
                    camera?.cameraControl?.enableTorch(true)
                    fragmentCameraBinding.penerjemahBtnFlashCamera.setImageResource(R.drawable.ic_flash_on)
                    true
                }
                true -> {
                    camera?.cameraControl?.enableTorch(false)
                    fragmentCameraBinding.penerjemahBtnFlashCamera.setImageResource(R.drawable.ic_flash_off)
                    false
                }
            }
        }
    }
}

