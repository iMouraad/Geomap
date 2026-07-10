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
