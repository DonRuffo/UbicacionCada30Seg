package com.example.gps20

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.net.http.HttpResponseCache
import com.google.android.gms.location.LocationRequest
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.gps20.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient // Import directo
import com.google.android.gms.location.LocationCallback // Import directo
import com.google.android.gms.location.LocationResult // Import directo
import com.google.android.gms.location.LocationServices // Import directo
import com.google.android.gms.location.Priority // Import para Priority
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.client.HttpClient // Asegúrate que es el import de Ktor
import io.ktor.client.engine.android.Android // O el engine que prefieras, ej: io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation // Plugin de Ktor
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var ubiButton: Button

    private lateinit var tvMensaje3: TextView // Para Longitud
    private lateinit var tvMensaje4: TextView // Para Latitud


    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: com.google.android.gms.location.LocationRequest // Especificando para evitar ambigüedad
    private lateinit var locationCallback: LocationCallback

    // --- Configuración de Supabase ---
    private val supabaseUrl = "https://mqpsbzrziuppiigkbiva.supabase.co"
    private val supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1xcHNienJ6aXVwcGlpZ2tiaXZhIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTEzMjIzNzIsImV4cCI6MjA2Njg5ODM3Mn0.yiCxB62ygVCmULMttRlrnC3HXmmh-vmCj4CAQYbD5zo"

    private val httpClient = HttpClient(Android) { // OJO: io.ktor.client.HttpClient
        install(ContentNegotiation) {
            json()
        }
    }

    // Lanzador para la solicitud de permisos de ubicación
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_LONG).show()
            tvMensaje3.text = "Permiso denegado"
            tvMensaje4.text = "Permiso denegado"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tvMensaje3 = binding.tvMensaje3
        tvMensaje4 = binding.tvMensaje4
        ubiButton = binding.ubiButton

        ubiButton.setOnClickListener {
            checkLocationPermissionAndStartUpdates()
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        updateUILocation(location)
                        savePointLocation(location.latitude, location.longitude) // Guardar en Supabase
                    }
                }
            }
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Configurar la solicitud de ubicación (Usando el builder correcto)
        locationRequest = com.google.android.gms.location.LocationRequest.Builder(30000L) // 30 segundos
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(15000L)
            .setMaxUpdateDelayMillis(35000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateUILocation(location)
                    saveLocationToSupabase(location.latitude, location.longitude) // Guardar en Supabase
                }
            }
        }
        checkLocationPermissionAndStartUpdates()
    }

    private fun checkLocationPermissionAndStartUpdates() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                startLocationUpdates()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                Toast.makeText(
                    this,
                    "Se necesita permiso de ubicación para mostrar latitud y longitud.",
                    Toast.LENGTH_LONG
                ).show()
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            else -> {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        tvMensaje3.text = "Esperando..."
        tvMensaje4.text = "Esperando..."
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                "Error de seguridad al iniciar actualizaciones: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateUILocation(location: Location) {
        tvMensaje3.text = "${location.longitude}"
        tvMensaje4.text = "${location.latitude}"
    }

    // --- Función para guardar en Supabase ---
    @Serializable
    data class LocationSupabaseData(
        val latitud: Double,
        val longitud: Double
    )

    private fun saveLocationToSupabase(latitude: Double, longitude: Double) {
        // Usar CoroutineScope para operaciones de red en un hilo de fondo
        CoroutineScope(Dispatchers.IO).launch {

            try {
                val locationData = LocationSupabaseData(latitud = latitude, longitud = longitude)

                val response: HttpResponse = httpClient.post("$supabaseUrl/rest/v1/Ubicaciones") { // Nombre de tu tabla
                    header("apikey", supabaseKey) // Clave de API anónima de Supabase
                    header("Authorization", "Bearer $supabaseKey") // Para RLS que usan la anon key como Bearer
                    contentType(ContentType.Application.Json) // Indicamos que enviamos JSON
                    accept(ContentType.Application.Json) // Indicamos que aceptamos JSON como respuesta
                    setBody(locationData) // El objeto a enviar, Ktor lo serializará a JSON
                }

                withContext(Dispatchers.Main) { // Volver al hilo principal para actualizar UI
                    if (response.status.isSuccess()) {
                        android.util.Log.i("Supabase", "Ubicación guardada con éxito: ${response.status}")
                    } else {
                        val errorBody = response.bodyAsText() // Intenta obtener más detalles del error
                        Toast.makeText(this@MainActivity, "Error al guardar ubicación: ${response.status} - $errorBody", Toast.LENGTH_LONG).show()
                        android.util.Log.e("Supabase", "Error al guardar: ${response.status}, Body: $errorBody")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Excepción al guardar ubicación: ${e.message}", Toast.LENGTH_LONG).show()
                    android.util.Log.e("Supabase", "Excepción al guardar: ${e.message}", e)
                }
            }
        }
    }

    private fun savePointLocation (latitude: Double, longitude: Double) {
        // Usar CoroutineScope para operaciones de red en un hilo de fondo
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val locationData = LocationSupabaseData(latitud = latitude, longitud = longitude)
                val response: HttpResponse = httpClient.post("$supabaseUrl/rest/v1/ubicacionesPuntos") { // Nombre de tu tabla
                    header("apikey", supabaseKey) // Clave de API anónima de Supabase
                    header("Authorization", "Bearer $supabaseKey") // Para RLS que usan la anon key como Bearer
                    contentType(ContentType.Application.Json) // Indicamos que enviamos JSON
                    accept(ContentType.Application.Json) // Indicamos que aceptamos JSON como respuesta
                    setBody(locationData) // El objeto a enviar, Ktor lo serializará a JSON
                }

                withContext(Dispatchers.Main) { // Volver al hilo principal para actualizar UI
                    if (response.status.isSuccess()) {
                        android.util.Log.i("Supabase", "Ubicación del punto guardada: ${response.status}")
                    } else {
                        val errorBody = response.bodyAsText() // Intenta obtener más detalles del error
                        Toast.makeText(this@MainActivity, "Error al guardar el punto: ${response.status} - $errorBody", Toast.LENGTH_LONG).show()
                        android.util.Log.e("Supabase", "Error al guardar: ${response.status}, Body: $errorBody")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Excepción al guardar ubicación: ${e.message}", Toast.LENGTH_LONG).show()
                    android.util.Log.e("Supabase", "Excepción al guardar: ${e.message}", e)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Detener las actualizaciones de ubicación cuando la actividad no está visible para ahorrar batería
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onResume() {
        super.onResume()
        // Reanudar las actualizaciones si el permiso está concedido
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {     startLocationUpdates()
                android.util.Log.d("MainActivityLifecycle", "onResume - Location updates started")
        } else {
            android.util.Log.d("MainActivityLifecycle", "onResume - Location permission not granted")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Asegurarse de detener las actualizaciones si la actividad se destruye completamente
        // aunque onPause ya debería haberlo hecho.
        fusedLocationClient.removeLocationUpdates(locationCallback)
        // Opcional: Cerrar el httpClient si no se va a usar más en toda la app
        // httpClient.close() // Considera el ciclo de vida de tu cliente si es compartido
        android.util.Log.d("MainActivityLifecycle", "onDestroy - Location updates removed and activity destroyed")
    }
}