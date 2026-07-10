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
