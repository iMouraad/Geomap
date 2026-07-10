# Monitoreo en Tiempo Real de Ubicación GPS con Firebase y Google Maps

Repositorio: https://github.com/iMouraad/Geomap

## Requisitos y evidencia de código

### 1. Captura de coordenadas — `FusedLocationProviderClient` cada 5 segundos

Se usa `LocationRequest.Builder` con prioridad de alta precisión y un intervalo fijo de 5000 ms. Cada vez que llega una lectura, se toma la última ubicación (`getLastLocation()`).

```java
private static final long INTERVALO_MS = 5000L;

private void iniciarCapturaUbicacion() {
    LocationRequest locationRequest =
            new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVALO_MS)
                    .setMinUpdateIntervalMillis(INTERVALO_MS)
                    .build();

    locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult locationResult) {
            Location location = locationResult.getLastLocation();
            if (location == null) return;

            tvLatitud.setText(getString(R.string.formato_latitud, location.getLatitude()));
            tvLongitud.setText(getString(R.string.formato_longitud, location.getLongitude()));

            Coordenada coordenada = new Coordenada(
                    location.getLatitude(),
                    location.getLongitude(),
                    System.currentTimeMillis(),
                    Build.MODEL);
            coordenadasRef.child(miDeviceId).push().setValue(coordenada);
        }
    };

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }
}
```

Dependencia en `app/build.gradle.kts`:

```kotlin
implementation(libs.play.services.location)
```

---

### 2. Almacenamiento en la nube — Firebase Realtime Database bajo la ruta `Coordenadas`

Se guarda cada lectura en Firebase Realtime Database, bajo el nodo raíz `Coordenadas` (con un subnivel por dispositivo, para poder monitorear varios celulares a la vez sin que se mezclen sus datos).

```java
coordenadasRef = FirebaseDatabase.getInstance().getReference("Coordenadas");
...
coordenadasRef.child(miDeviceId).push().setValue(coordenada);
```

Modelo de datos guardado (`Coordenada.java`):

```java
public class Coordenada {

    public Double latitud;
    public Double longitud;
    public long timestamp;
    public String dispositivo;

    public Coordenada() {
    }

    public Coordenada(double latitud, double longitud, long timestamp, String dispositivo) {
        this.latitud = latitud;
        this.longitud = longitud;
        this.timestamp = timestamp;
        this.dispositivo = dispositivo;
    }
}
```

Dependencias en `app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.google.services)
}
...
implementation(platform(libs.firebase.bom))
implementation(libs.firebase.database)
```

---

### 3. Visualización en el mapa — marcador dinámico según cambios en Firebase

Un `ChildEventListener` escucha en tiempo real los cambios en `Coordenadas` y actualiza (o crea) el marcador correspondiente en Google Maps cada vez que llega un dato nuevo, además de dibujar la trayectoria recorrida con una polilínea.

```java
private void escucharCambiosEnFirebase() {
    coordenadasRef.addChildEventListener(new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dispositivoSnapshot, String previousChildName) {
            if (!dispositivoSnapshot.hasChildren()) {
                return;
            }
            escucharPuntosDeDispositivo(dispositivoSnapshot.getKey());
        }
        // onChildChanged, onChildRemoved, onChildMoved, onCancelled ...
    });
}

private void escucharPuntosDeDispositivo(String deviceId) {
    coordenadasRef.child(deviceId).addChildEventListener(new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
            Coordenada coordenada = snapshot.getValue(Coordenada.class);
            if (coordenada == null || coordenada.latitud == null || coordenada.longitud == null) {
                return;
            }

            Recorrido recorrido = recorridosPorDispositivo.computeIfAbsent(deviceId,
                    id -> new Recorrido(COLORES[recorridosPorDispositivo.size() % COLORES.length]));

            LatLng punto = new LatLng(coordenada.latitud, coordenada.longitud);
            recorrido.puntos.add(punto);

            if (recorrido.polyline == null) {
                recorrido.polyline = mMap.addPolyline(new PolylineOptions()
                        .addAll(recorrido.puntos)
                        .width(10f)
                        .color(recorrido.color));
            } else {
                recorrido.polyline.setPoints(recorrido.puntos);
            }

            String etiqueta = deviceId.equals(miDeviceId)
                    ? "Yo (este dispositivo)"
                    : (coordenada.dispositivo != null ? coordenada.dispositivo : "Dispositivo remoto");

            if (recorrido.marcador == null) {
                recorrido.marcador = mMap.addMarker(new MarkerOptions()
                        .position(punto)
                        .title(etiqueta));
            } else {
                recorrido.marcador.setPosition(punto);
            }
        }
        // onChildChanged, onChildRemoved, onChildMoved, onCancelled ...
    });
}
```

Configuración inicial del mapa (vista satelital centrada en la UTEQ):

```java
@Override
public void onMapReady(GoogleMap googleMap) {
    mMap = googleMap;
    mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
    mMap.getUiSettings().setZoomControlsEnabled(true);
    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(UTEQ, 17f));

    escucharCambiosEnFirebase();
}
```

---

### 4. Permisos y seguridad — `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` y manejo de denegación

Permisos declarados en `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
```

Solicitud en tiempo de ejecución y manejo de la denegación:

```java
private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean granted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                    || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
            if (granted) {
                onLocationPermissionGranted();
            } else {
                Toast.makeText(this,
                        "Sin permisos de ubicación no se puede monitorear el GPS en tiempo real",
                        Toast.LENGTH_LONG).show();
            }
        });

private void verificarYPedirPermisos() {
    boolean fineGranted = ContextCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    boolean coarseGranted = ContextCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

    if (fineGranted || coarseGranted) {
        onLocationPermissionGranted();
    } else {
        permissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }
}
```

Verificación adicional de que el GPS del sistema esté encendido (no solo el permiso de la app):

```java
private boolean estaUbicacionActivada() {
    LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    if (locationManager == null) {
        return false;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return locationManager.isLocationEnabled();
    }
    return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
}
```

---

### 5. Interfaz básica — latitud/longitud en pantalla + mapa con marcador

Layout (`activity_main.xml`): dos `TextView` para lat/lng, un botón para iniciar el envío, y el `SupportMapFragment` debajo.

```xml
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/main"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:id="@+id/coordsPanel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp"
        android:background="?attr/colorSurface"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <TextView
            android:id="@+id/tvLatitud"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="16sp"
            android:text="Latitud: --" />

        <TextView
            android:id="@+id/tvLongitud"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="16sp"
            android:text="Longitud: --" />

        <Button
            android:id="@+id/btnIniciarSeguimiento"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="Iniciar mi seguimiento GPS" />

    </LinearLayout>

    <fragment
        android:id="@+id/map"
        android:name="com.google.android.gms.maps.SupportMapFragment"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/coordsPanel" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

Los `TextView` se actualizan directamente al capturar cada lectura GPS local (ver punto 1, `onLocationResult`).

---

## Código fuente completo

### `AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.MyApplication">

        <meta-data
            android:name="com.google.android.geo.API_KEY"
            android:value="${MAPS_API_KEY}" />

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

### `app/build.gradle.kts` (dependencias relevantes)

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

dependencies {
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database)
}
```

### `Coordenada.java`

```java
package com.example.myapplication;

public class Coordenada {

    public Double latitud;
    public Double longitud;
    public long timestamp;
    public String dispositivo;

    public Coordenada() {
    }

    public Coordenada(double latitud, double longitud, long timestamp, String dispositivo) {
        this.latitud = latitud;
        this.longitud = longitud;
        this.timestamp = timestamp;
        this.dispositivo = dispositivo;
    }
}
```

### `MainActivity.java`

```java
package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final LatLng UTEQ = new LatLng(-1.012778, -79.469167);
    private static final long INTERVALO_MS = 5000L;
    private static final int[] COLORES = {
            Color.BLUE, Color.RED, Color.MAGENTA, Color.parseColor("#FF9800"), Color.CYAN
    };

    private static class Recorrido {
        final List<LatLng> puntos = new ArrayList<>();
        Marker marcador;
        Polyline polyline;
        final int color;

        Recorrido(int color) {
            this.color = color;
        }
    }

    private GoogleMap mMap;
    private final Map<String, Recorrido> recorridosPorDispositivo = new HashMap<>();
    private boolean camaraCentrada = false;
    private String miDeviceId;
    private TextView tvLatitud;
    private TextView tvLongitud;
    private Button btnIniciarSeguimiento;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private DatabaseReference coordenadasRef;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (granted) {
                    onLocationPermissionGranted();
                } else {
                    Toast.makeText(this,
                            "Sin permisos de ubicación no se puede monitorear el GPS en tiempo real",
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        tvLatitud = findViewById(R.id.tvLatitud);
        tvLongitud = findViewById(R.id.tvLongitud);
        btnIniciarSeguimiento = findViewById(R.id.btnIniciarSeguimiento);
        btnIniciarSeguimiento.setOnClickListener(v -> verificarYPedirPermisos());

        miDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        coordenadasRef = FirebaseDatabase.getInstance().getReference("Coordenadas");

        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
        mMap.getUiSettings().setZoomControlsEnabled(true);
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(UTEQ, 17f));

        escucharCambiosEnFirebase();
    }

    private void verificarYPedirPermisos() {
        boolean fineGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (fineGranted || coarseGranted) {
            onLocationPermissionGranted();
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private void onLocationPermissionGranted() {
        if (!estaUbicacionActivada()) {
            Toast.makeText(this,
                    "El GPS/Ubicación está apagado en el sistema. Actívalo para poder rastrear.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
        }
        iniciarCapturaUbicacion();
        btnIniciarSeguimiento.setText("Enviando mi ubicación...");
        btnIniciarSeguimiento.setEnabled(false);
    }

    private boolean estaUbicacionActivada() {
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        }
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void iniciarCapturaUbicacion() {
        LocationRequest locationRequest =
                new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVALO_MS)
                        .setMinUpdateIntervalMillis(INTERVALO_MS)
                        .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location == null) return;

                tvLatitud.setText(getString(R.string.formato_latitud, location.getLatitude()));
                tvLongitud.setText(getString(R.string.formato_longitud, location.getLongitude()));

                Coordenada coordenada = new Coordenada(
                        location.getLatitude(),
                        location.getLongitude(),
                        System.currentTimeMillis(),
                        Build.MODEL);
                coordenadasRef.child(miDeviceId).push().setValue(coordenada);
            }
        };

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    private void escucharCambiosEnFirebase() {
        coordenadasRef.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dispositivoSnapshot, String previousChildName) {
                if (!dispositivoSnapshot.hasChildren()) {
                    return;
                }
                escucharPuntosDeDispositivo(dispositivoSnapshot.getKey());
            }

            @Override
            public void onChildChanged(DataSnapshot snapshot, String previousChildName) {
            }

            @Override
            public void onChildRemoved(DataSnapshot snapshot) {
            }

            @Override
            public void onChildMoved(DataSnapshot snapshot, String previousChildName) {
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(MainActivity.this,
                        "Error al leer Firebase: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void escucharPuntosDeDispositivo(String deviceId) {
        coordenadasRef.child(deviceId).addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
                Coordenada coordenada = snapshot.getValue(Coordenada.class);
                if (coordenada == null || coordenada.latitud == null || coordenada.longitud == null) {
                    return;
                }

                Recorrido recorrido = recorridosPorDispositivo.computeIfAbsent(deviceId,
                        id -> new Recorrido(COLORES[recorridosPorDispositivo.size() % COLORES.length]));

                LatLng punto = new LatLng(coordenada.latitud, coordenada.longitud);
                recorrido.puntos.add(punto);

                if (recorrido.polyline == null) {
                    recorrido.polyline = mMap.addPolyline(new PolylineOptions()
                            .addAll(recorrido.puntos)
                            .width(10f)
                            .color(recorrido.color));
                } else {
                    recorrido.polyline.setPoints(recorrido.puntos);
                }

                String etiqueta = deviceId.equals(miDeviceId)
                        ? "Yo (este dispositivo)"
                        : (coordenada.dispositivo != null ? coordenada.dispositivo : "Dispositivo remoto");

                if (recorrido.marcador == null) {
                    recorrido.marcador = mMap.addMarker(new MarkerOptions()
                            .position(punto)
                            .title(etiqueta));
                } else {
                    recorrido.marcador.setPosition(punto);
                }

                if (!camaraCentrada) {
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(punto, 18f));
                    camaraCentrada = true;
                }
            }

            @Override
            public void onChildChanged(DataSnapshot snapshot, String previousChildName) {
            }

            @Override
            public void onChildRemoved(DataSnapshot snapshot) {
            }

            @Override
            public void onChildMoved(DataSnapshot snapshot, String previousChildName) {
            }

            @Override
            public void onCancelled(DatabaseError error) {
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}
```

### `activity_main.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/main"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:context=".MainActivity">

    <LinearLayout
        android:id="@+id/coordsPanel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp"
        android:background="?attr/colorSurface"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <TextView
            android:id="@+id/tvLatitud"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="16sp"
            android:text="Latitud: --" />

        <TextView
            android:id="@+id/tvLongitud"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="16sp"
            android:text="Longitud: --" />

        <Button
            android:id="@+id/btnIniciarSeguimiento"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="Iniciar mi seguimiento GPS" />

    </LinearLayout>

    <fragment
        android:id="@+id/map"
        android:name="com.google.android.gms.maps.SupportMapFragment"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/coordsPanel" />

</androidx.constraintlayout.widget.ConstraintLayout>
```
