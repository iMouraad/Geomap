package com.example.myapplication;

public class Coordenada {

    public Double latitud;
    public Double longitud;
    public long timestamp;

    public Coordenada() {
    }

    public Coordenada(double latitud, double longitud, long timestamp) {
        this.latitud = latitud;
        this.longitud = longitud;
        this.timestamp = timestamp;
    }
}
