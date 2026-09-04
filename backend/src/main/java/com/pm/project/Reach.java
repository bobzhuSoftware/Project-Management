package com.pm.project;

/**
 * How far a launch's named address is reachable (Rung 1/2/3).
 * LOCAL = {@code <alias>.localhost} on this machine only (default, current behaviour).
 * WIFI  = also announced as {@code <alias>.local} on the LAN (phones on same Wi-Fi).
 * INTERNET = also exposed via a temporary public tunnel.
 */
public enum Reach {
    LOCAL,
    WIFI,
    INTERNET
}
