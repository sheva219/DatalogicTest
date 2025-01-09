package com.example.datalogictest

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

import com.datalogic.decode.BarcodeManager
import com.datalogic.decode.DecodeException
import com.datalogic.decode.DecodeResult
import com.datalogic.decode.ReadListener
import com.datalogic.decode.StartListener
import com.datalogic.decode.StopListener
import com.datalogic.decode.TimeoutListener
import com.datalogic.decode.configuration.DisplayNotification
import com.datalogic.device.ErrorManager

import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), ReadListener, StartListener, TimeoutListener, StopListener  {

	private var showScanResult: EditText? = null
	private var status: TextView? = null
	private var mScan: Button? = null
	private var statusTextColor: Int = 0

	private var mBarcodeManager: BarcodeManager? = null
	private var ignoreStop = false
	private var previousNotification: Boolean = false
	private var mToast: Toast? = null

	private lateinit var mBarcodeText : TextView

	private lateinit var cameraExecutor: ExecutorService

	private val activityResultLauncher =
		registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
			var permissionGranted = true
			permissions.entries.forEach {
				if (!it.value) {
					permissionGranted = false
				}
			}
			if (!permissionGranted) {
				Toast.makeText(baseContext, "Permission request denied", Toast.LENGTH_SHORT).show()
			} else {
				startCamera()
			}
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val window = window
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		setContentView(R.layout.activity_main)

		ErrorManager.enableExceptions(true)

		if (allPermissionsGranted()) {
			startCamera()
		} else {
			requestPermissions()
		}

		cameraExecutor = Executors.newSingleThreadExecutor()

		setupView()

		mBarcodeText = findViewById<TextView>(R.id.scan_result)
		mBarcodeText.showSoftInputOnFocus = false
	}

	private fun setupView() {
		showScanResult = findViewById<EditText>(R.id.scan_result)

		status = findViewById<TextView>(R.id.scanner_status)
		statusTextColor = status!!.currentTextColor

		mScan = findViewById<Button>(R.id.scanBtn)
		mScan!!.setOnTouchListener { v, event ->
			if (event.action == MotionEvent.ACTION_DOWN) {
				try {
					Log.e("Tset", "** Click Scan button")
					mScan!!.isPressed = true
					mBarcodeManager!!.startDecode()
				} catch (e: Exception) {
					Log.e(TAG, "Action DOWN", e)
					showMessage("ERROR! Check logcat")
				}

			} else if (event.action == MotionEvent.ACTION_UP) {
				try {
					mBarcodeManager!!.stopDecode()
					mScan!!.isPressed = false
				} catch (e: Exception) {
					Log.e(TAG, "Action UP", e)
					showMessage("ERROR! Check logcat")
				}

				v.performClick()
			}
			true
		}
	}

	private fun initScan() {
		try {
			mBarcodeManager = BarcodeManager()
		} catch (e: Exception) {
			Log.e(TAG, "Error while creating BarcodeManager")
			showMessage("ERROR! Check logcat")
			finish()
			return
		}

		val dn = DisplayNotification(mBarcodeManager)
		if (dn.enable != null) {
			previousNotification = dn.enable.get()
			dn.enable.set(false)
			try {
				dn.store(mBarcodeManager, false)
			} catch (e: Exception) {
				Log.e(TAG, "Cannot disable Display Notification", e)
			}
		}
		registerListeners()
	}

	private fun registerListeners() {
		try {
			mBarcodeManager!!.addReadListener(this)
			mBarcodeManager!!.addStartListener(this)
			mBarcodeManager!!.addStopListener(this)
			mBarcodeManager!!.addTimeoutListener(this)
		} catch (e: Exception) {
			Log.e(TAG, "Cannot add listener, the app won't work")
			showMessage("ERROR! Check logcat")
			finish()
		}

	}

	private fun releaseListeners() {
		try {
			mBarcodeManager!!.removeReadListener(this)
			mBarcodeManager!!.removeStartListener(this)
			mBarcodeManager!!.removeStopListener(this)
			mBarcodeManager!!.removeTimeoutListener(this)
		} catch (e: DecodeException) {
			Log.e(TAG, "Cannot remove listeners, the app won't work", e)
			showMessage("ERROR! Check logcat")
			finish()
		}

	}

	override fun onPause() {
		super.onPause()

		try {
			if (mBarcodeManager != null) {
				mBarcodeManager!!.stopDecode()

				releaseListeners()

				val dn = DisplayNotification(mBarcodeManager)
				dn.enable?.let { enableProperty ->
					enableProperty.set(previousNotification)
					dn.store(mBarcodeManager, false)
				}

				mBarcodeManager = null
			} else {
				Log.w(TAG, "mBarcodeManager is null in onPause.")
			}
		} catch (e: Exception) {
			Log.e(TAG, "Cannot detach from Scanner correctly", e)
			showMessage("ERROR! Check logcat")
		}
	}

	override fun onResume() {
		super.onResume()
		initScan()
		showScanResult!!.hint = resources.getText(R.string.scanner_hint)
	}

	override fun onScanStarted() {
		status!!.setTextColor(Color.RED)
		val s = "Scanning"
		status!!.text = s

		showScanResult!!.setText("")
		showMessage("Scanner Started")
		Log.d(TAG, "Scan start")
	}

	override fun onRead(result: DecodeResult) {
		status!!.setTextColor(Color.rgb(51, 153, 51))
		status!!.text = "Result"

		showScanResult!!.append("Barcode Type: " + result.barcodeID + "\n")
		val string = result.text
		if (string != null) {
			showScanResult!!.append("Result: $string")
		}
		ignoreStop = true

		val bData = result.rawData
		var iData = IntArray(0)
		for(x in bData){
			iData += x.toInt()
		}

		val bDataHex = encodeHex(iData)
		val text = result.text
		val symb = result.barcodeID.toString()

		Log.d(TAG, "Scan read")
		Log.d(TAG, "Symb: $symb")
		Log.d(TAG, "Data: $text")
		Log.d(TAG, "Data[]: $bData")
		Log.d(TAG, "As hex: $bDataHex")

		showMessage("Scanner Read")
	}

	override fun onScanStopped() {
		if (!ignoreStop) {
			status!!.setTextColor(statusTextColor)
			val r = "Ready"
			status!!.text = r
			showMessage("Scanner Stopped")
		} else {
			ignoreStop = false
		}
	}

	override fun onScanTimeout() {
		status!!.setTextColor(Color.WHITE)
		val t = "Timeout"
		status!!.text = t
		ignoreStop = true

		showMessage("Scanning timed out")
		Log.d(TAG, "Scan timeout")
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.main, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.action_settings -> {
				val intent = Intent(this, SettingsActivity::class.java)
				startActivity(intent)
				return true
			}
			R.id.action_release_listeners -> {
				releaseListeners()
				return true
			}
			R.id.action_register_listeners -> {
				registerListeners()
				return true
			}
			else -> return super.onOptionsItemSelected(item)
		}
	}

	private fun encodeHex(data: IntArray?): String {
		if (data == null)
			return ""

		val hexString = StringBuffer()
		hexString.append('[')
		for (i in data.indices) {
			hexString.append(' ')
			val hex = Integer.toHexString(0xFF and data[i])
			if (hex.length == 1) {
				hexString.append('0')
			}
			hexString.append(hex)
		}
		hexString.append(']')
		return hexString.toString()
	}

	private fun showMessage(s: String) {
		if (mToast == null || mToast!!.view?.windowVisibility != View.VISIBLE) {
			mToast = Toast.makeText(this, s, Toast.LENGTH_SHORT)
			mToast!!.show()
		} else {
			mToast!!.setText(s)
		}
	}

	companion object {

		internal const val TAG = "Test-Scanner"

		private val REQUIRED_PERMISSIONS = arrayOf(
			Manifest.permission.CAMERA
		)

	}

	private fun startCamera() {
		val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

		cameraProviderFuture.addListener({
			val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

			val previewView = findViewById<PreviewView>(R.id.viewFinder)

			val preview = Preview.Builder().build().also {
				it.setSurfaceProvider(previewView.surfaceProvider)
			}

			val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

			try {
				cameraProvider.unbindAll()

				cameraProvider.bindToLifecycle(
					this, cameraSelector, preview
				)
			} catch (exc: Exception) {
				Log.e(TAG, "Use case binding failed", exc)
			}
		}, ContextCompat.getMainExecutor(this))
	}


	private fun requestPermissions() {
		activityResultLauncher.launch(REQUIRED_PERMISSIONS)
	}

	private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
		ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
	}

	override fun onDestroy() {
		super.onDestroy()
		cameraExecutor.shutdown()
	}

}