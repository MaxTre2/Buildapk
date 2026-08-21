package ru.geoportal40.azsmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TARGET_URL = "https://azs.geoportal40.ru"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val DEFAULT_TIMEOUT_MS = 15000L

        // Overrides navigator.geolocation to route through the native AndroidGeo bridge,
        // since WebView's built-in HTML5 geolocation is unreliable on many devices.
        const val GEO_SHIM_JS = """
        (function() {
          if (window.__geoShimInstalled) return;
          window.__geoShimInstalled = true;
          window.__geoCallbacks = {};
          window.__geoNextId = 1;
          window.__geoWatchCallbacks = {};
          window.__geoWatchNextId = 1;

          window.__geoResolve = function(id, lat, lon, acc, alt, heading, speed, ts) {
            var cb = window.__geoCallbacks[id];
            if (!cb) return;
            delete window.__geoCallbacks[id];
            cb.success({
              coords: { latitude: lat, longitude: lon, accuracy: acc, altitude: alt,
                        altitudeAccuracy: null, heading: heading, speed: speed },
              timestamp: ts
            });
          };
          window.__geoReject = function(id, code, message) {
            var cb = window.__geoCallbacks[id];
            if (!cb) return;
            delete window.__geoCallbacks[id];
            if (cb.error) cb.error({ code: code, message: message });
          };
          window.__geoWatchResolve = function(watchId, lat, lon, acc, alt, heading, speed, ts) {
            var cb = window.__geoWatchCallbacks[watchId];
            if (!cb) return;
            cb.success({
              coords: { latitude: lat, longitude: lon, accuracy: acc, altitude: alt,
                        altitudeAccuracy: null, heading: heading, speed: speed },
              timestamp: ts
            });
          };
          window.__geoWatchReject = function(watchId, code, message) {
            var cb = window.__geoWatchCallbacks[watchId];
            if (cb && cb.error) cb.error({ code: code, message: message });
          };

          if (navigator.geolocation) {
            navigator.geolocation.getCurrentPosition = function(success, error, options) {
              var id = window.__geoNextId++;
              window.__geoCallbacks[id] = { success: success, error: error };
              var timeout = (options && options.timeout) || 15000;
              var highAcc = !!(options && options.enableHighAccuracy);
              AndroidGeo.getCurrentPosition(id, highAcc, timeout);
            };
            navigator.geolocation.watchPosition = function(success, error, options) {
              var watchId = window.__geoWatchNextId++;
              window.__geoWatchCallbacks[watchId] = { success: success, error: error };
              var highAcc = !!(options && options.enableHighAccuracy);
              AndroidGeo.watchPosition(watchId, highAcc);
              return watchId;
            };
            navigator.geolocation.clearWatch = function(watchId) {
              delete window.__geoWatchCallbacks[watchId];
              AndroidGeo.clearWatch(watchId);
            };
          }
        })();
        """
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineLayout: View
    private lateinit var locationManager: LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Requests that are waiting on the Android runtime location permission.
    private data class PendingRequest(
        val id: Int,
        val isWatch: Boolean,
        val highAccuracy: Boolean,
        val timeoutMs: Long
    )
    private val pendingRequests = mutableListOf<PendingRequest>()
    private val activeWatchListeners = mutableMapOf<Int, LocationListener>()

    // -------------------- Lifecycle --------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        offlineLayout = findViewById(R.id.offlineLayout)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        findViewById<View>(R.id.retryButton).setOnClickListener { loadSite() }
        swipeRefresh.setOnRefreshListener { webView.reload() }

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadSite()
    }

    override fun onDestroy() {
        activeWatchListeners.values.forEach { locationManager.removeUpdates(it) }
        activeWatchListeners.clear()
        webView.destroy()
        super.onDestroy()
    }

    // -------------------- WebView setup --------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false

        // Native geolocation bridge exposed to the page as `AndroidGeo`.
        webView.addJavascriptInterface(GeoBridge(), "AndroidGeo")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                return if (url.contains("geoportal40.ru")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: Exception) {
                        // no app can handle it, ignore
                    }
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Inject as early as possible so the page's own geolocation calls
                // go through our native bridge instead of WebView's broken one.
                view.evaluateJavascript(GEO_SHIM_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE
                // Safety net in case the shim wasn't applied early enough.
                view.evaluateJavascript(GEO_SHIM_JS, null)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    showOffline()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Deny camera/mic style requests some map widgets ask for.
                runOnUiThread { request.deny() }
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            if (URLUtil.isValidUrl(url)) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadSite() {
        if (isOnline()) {
            offlineLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(TARGET_URL)
        } else {
            showOffline()
        }
    }

    private fun showOffline() {
        swipeRefresh.isRefreshing = false
        progressBar.visibility = View.GONE
        webView.visibility = View.GONE
        offlineLayout.visibility = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // -------------------- Native geolocation bridge --------------------

    /**
     * Exposed to the page's JavaScript as `AndroidGeo`. The page never talks to this
     * directly - it goes through the `navigator.geolocation` shim injected by GEO_SHIM_JS.
     */
    inner class GeoBridge {
        @JavascriptInterface
        fun getCurrentPosition(requestId: Int, highAccuracy: Boolean, timeoutMs: Long) {
            runOnUiThread {
                fetchOneShotLocation(requestId, highAccuracy, if (timeoutMs > 0) timeoutMs else DEFAULT_TIMEOUT_MS)
            }
        }

        @JavascriptInterface
        fun watchPosition(watchId: Int, highAccuracy: Boolean) {
            runOnUiThread { startWatch(watchId, highAccuracy) }
        }

        @JavascriptInterface
        fun clearWatch(watchId: Int) {
            runOnUiThread { stopWatch(watchId) }
        }
    }

    private fun bestProvider(highAccuracy: Boolean): String? {
        val gpsOk = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netOk = try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
        return when {
            highAccuracy && gpsOk -> LocationManager.GPS_PROVIDER
            netOk -> LocationManager.NETWORK_PROVIDER
            gpsOk -> LocationManager.GPS_PROVIDER
            else -> null
        }
    }

    private fun fetchOneShotLocation(requestId: Int, highAccuracy: Boolean, timeoutMs: Long) {
        if (!hasLocationPermission()) {
            pendingRequests.add(PendingRequest(requestId, false, highAccuracy, timeoutMs))
            requestLocationPermission()
            return
        }
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            rejectGeo(requestId, 2, "Location services are disabled on this device")
            return
        }
        val provider = bestProvider(highAccuracy)
        if (provider == null) {
            rejectGeo(requestId, 2, "No location provider available")
            return
        }

        var resolved = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (resolved) return
                resolved = true
                locationManager.removeUpdates(this)
                resolveGeo(requestId, location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                if (!resolved) {
                    resolved = true
                    locationManager.removeUpdates(this)
                    rejectGeo(requestId, 2, "Provider disabled")
                }
            }
        }

        try {
            val lastKnown = try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            }
            locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())

            mainHandler.postDelayed({
                if (!resolved) {
                    resolved = true
                    locationManager.removeUpdates(listener)
                    if (lastKnown != null) {
                        resolveGeo(requestId, lastKnown)
                    } else {
                        rejectGeo(requestId, 3, "Timed out waiting for a location fix")
                    }
                }
            }, timeoutMs)
        } catch (e: SecurityException) {
            rejectGeo(requestId, 1, "Permission denied")
        }
    }

    private fun startWatch(watchId: Int, highAccuracy: Boolean) {
        if (!hasLocationPermission()) {
            pendingRequests.add(PendingRequest(watchId, true, highAccuracy, 0))
            requestLocationPermission()
            return
        }
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            rejectWatchGeo(watchId, 2, "Location services are disabled on this device")
            return
        }
        val provider = bestProvider(highAccuracy)
        if (provider == null) {
            rejectWatchGeo(watchId, 2, "No location provider available")
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                resolveWatchGeo(watchId, location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                rejectWatchGeo(watchId, 2, "Provider disabled")
            }
        }

        try {
            locationManager.requestLocationUpdates(provider, 2000L, 5f, listener, Looper.getMainLooper())
            activeWatchListeners[watchId] = listener
        } catch (e: SecurityException) {
            rejectWatchGeo(watchId, 1, "Permission denied")
        }
    }

    private fun stopWatch(watchId: Int) {
        activeWatchListeners.remove(watchId)?.let { locationManager.removeUpdates(it) }
    }

    private fun resolveGeo(requestId: Int, location: Location) {
        runOnUiThread {
            val js = "window.__geoResolve(%d,%s,%s,%s,%s,%s,%s,%d);".format(
                requestId,
                location.latitude,
                location.longitude,
                location.accuracy,
                if (location.hasAltitude()) location.altitude.toString() else "null",
                if (location.hasBearing()) location.bearing.toString() else "null",
                if (location.hasSpeed()) location.speed.toString() else "null",
                location.time
            )
            webView.evaluateJavascript(js, null)
        }
    }

    private fun rejectGeo(requestId: Int, code: Int, message: String) {
        runOnUiThread {
            val js = "window.__geoReject(${requestId},${code},${JSONObject.quote(message)});"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun resolveWatchGeo(watchId: Int, location: Location) {
        runOnUiThread {
            val js = "window.__geoWatchResolve(%d,%s,%s,%s,%s,%s,%s,%d);".format(
                watchId,
                location.latitude,
                location.longitude,
                location.accuracy,
                if (location.hasAltitude()) location.altitude.toString() else "null",
                if (location.hasBearing()) location.bearing.toString() else "null",
                if (location.hasSpeed()) location.speed.toString() else "null",
                location.time
            )
            webView.evaluateJavascript(js, null)
        }
    }

    private fun rejectWatchGeo(watchId: Int, code: Int, message: String) {
        runOnUiThread {
            val js = "window.__geoWatchReject(${watchId},${code},${JSONObject.quote(message)});"
            webView.evaluateJavascript(js, null)
        }
    }

    // -------------------- Runtime permission --------------------

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED

            val queued = pendingRequests.toList()
            pendingRequests.clear()
            for (req in queued) {
                if (granted) {
                    if (req.isWatch) startWatch(req.id, req.highAccuracy)
                    else fetchOneShotLocation(req.id, req.highAccuracy, req.timeoutMs)
                } else {
                    if (req.isWatch) rejectWatchGeo(req.id, 1, "Permission denied")
                    else rejectGeo(req.id, 1, "Permission denied")
                }
            }
        }
    }

}