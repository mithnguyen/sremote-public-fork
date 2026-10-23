package com.sremote.app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.function.Consumer;

public final class Tailscale {
    public static final String PKG = "com.tailscale.ipn";
    public static final String PLAY_URL =
            "https://play.google.com/store/apps/details?id=" + PKG;

    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Tailnet IPv4 seen on this device, else null.
     * Primary path: ConnectivityManager VPN transports — the only reliable way
     * on Android 10+, where NetworkInterface enumeration hides VPN interfaces.
     */
    public static String tailnetIp(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps == null
                            || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                        continue;
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    String v6 = null;
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        String ip = a.getHostAddress();
                        if (a instanceof Inet4Address && isTailscaleV4(ip)) return ip;
                        if (ip != null && ip.startsWith("fd7a:115c:a1e0:")) v6 = ip;
                    }
                    if (v6 != null) return v6; // v6-only tailnet still counts
                }
            }
        } catch (Throwable ignored) {}

        // fallback: raw interface scan (older Android)
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (isTailscaleV4(ip)) return ip;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** any VPN transport is up (Tailscale or other) — useful when link addresses are redacted */
    public static boolean vpnUp(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                    return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** host reached through the tailnet (100.64/10, *.ts.net, bare MagicDNS name) — LAN / public hosts don't need it */
    public static boolean viaTailnet(Host h) {
        String hp = h.hostPort();
        String host = hp.startsWith("[") ? hp : hp.replaceFirst(":\\d+$", "");
        if (isTailscaleV4(host)) return true;
        String l = host.toLowerCase(java.util.Locale.ROOT);
        if (l.endsWith(".ts.net")) return true;
        return !l.contains(".") && !l.contains(":") && !l.equals("localhost");
    }

    public static boolean isTailscaleV4(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 100 && b >= 64 && b <= 127; // 100.64.0.0/10
        } catch (NumberFormatException e) { return false; }
    }

    /** opens Play Store page, falls back to the web URL */
    public static void openPlayStore(Context c) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + PKG)));
        } catch (ActivityNotFoundException e) {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_URL)));
        }
    }

    /** launches the Tailscale app; returns false if it can't be launched */
    public static boolean openApp(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(PKG);
        if (i == null) return false;
        c.startActivity(i);
        return true;
    }

    // ---------- remote control of the official app ----------
    // Tailscale's exported IPNReceiver takes CONNECT_VPN / DISCONNECT_VPN (the
    // same hook Tasker uses), so the tunnel can be toggled without leaving
    // S-remote. Needs a one-time login + VPN consent inside Tailscale itself.
    private static final String RECEIVER = PKG + ".IPNReceiver";
    static final String CONNECT = PKG + ".CONNECT_VPN", DISCONNECT = PKG + ".DISCONNECT_VPN";

    /** tunnel looks up (tailnet address, or any VPN when addresses are redacted) */
    public static boolean up(Context c) { return tailnetIp(c) != null || vpnUp(c); }

    private static boolean send(Context c, String action) {
        if (!installed(c)) return false;
        try {
            c.sendBroadcast(new Intent(action).setClassName(PKG, RECEIVER));
            return true;
        } catch (Exception e) { return false; }
    }

    public static boolean connect(Context c) { return send(c, CONNECT); }
    public static boolean disconnect(Context c) { return send(c, DISCONNECT); }

    /**
     * ask Tailscale to connect, then poll until the tunnel shows up or the
     * timeout passes; {@code done} runs on the main thread with the outcome
     */
    public static void connectThen(Context c, long timeoutMs, Consumer<Boolean> done) {
        if (up(c)) { done.accept(true); return; }
        if (!connect(c)) { done.accept(false); return; }
        waitFor(c, true, timeoutMs, done);
    }

    public static void disconnectThen(Context c, long timeoutMs, Consumer<Boolean> done) {
        if (!disconnect(c)) { done.accept(false); return; }
        waitFor(c, false, timeoutMs, done);
    }

    private static void waitFor(Context c, boolean wantUp, long timeoutMs, Consumer<Boolean> done) {
        Context app = c.getApplicationContext();
        Handler h = new Handler(Looper.getMainLooper());
        long until = System.currentTimeMillis() + timeoutMs;
        h.postDelayed(new Runnable() {
            @Override public void run() {
                boolean u = up(app);
                if (u == wantUp) { done.accept(true); return; }
                if (System.currentTimeMillis() > until) { done.accept(false); return; }
                h.postDelayed(this, 350);
            }
        }, 300);
    }
}
