package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
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
import java.util.List;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final LatLng UTEQ = new LatLng(-1.012778, -79.469167);
    private static final long INTERVALO_MS = 5000L;

    private GoogleMap mMap;
    private Marker marcadorActual;
    private Polyline recorridoPolyline;
    private final List<LatLng> recorrido = new ArrayList<>();
    private boolean camaraCentrada = false;
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
        }
        iniciarCapturaUbicacion();
        btnIniciarSeguimiento.setText("Enviando mi ubicación...");
        btnIniciarSeguimiento.setEnabled(false);
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

                Coordenada coordenada = new Coordenada(
                        location.getLatitude(),
                        location.getLongitude(),
                        System.currentTimeMillis());
                coordenadasRef.push().setValue(coordenada);
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
            public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
                if (!snapshot.hasChildren()) {
                    return;
                }

                Coordenada coordenada = snapshot.getValue(Coordenada.class);
                if (coordenada == null || coordenada.latitud == null || coordenada.longitud == null) {
                    return;
                }

                LatLng punto = new LatLng(coordenada.latitud, coordenada.longitud);
                recorrido.add(punto);

                if (recorridoPolyline == null) {
                    recorridoPolyline = mMap.addPolyline(new PolylineOptions()
                            .addAll(recorrido)
                            .width(10f)
                            .color(Color.BLUE));
                } else {
                    recorridoPolyline.setPoints(recorrido);
                }

                if (marcadorActual == null) {
                    marcadorActual = mMap.addMarker(new MarkerOptions()
                            .position(punto)
                            .title("Ubicación actual"));
                } else {
                    marcadorActual.setPosition(punto);
                }

                tvLatitud.setText(getString(R.string.formato_latitud, coordenada.latitud));
                tvLongitud.setText(getString(R.string.formato_longitud, coordenada.longitud));

                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(punto, camaraCentrada ? mMap.getCameraPosition().zoom : 18f));
                camaraCentrada = true;
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}
