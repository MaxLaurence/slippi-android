package org.dolphinemu.dolphinemu.utils;

import android.app.Service;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Build;

import org.dolphinemu.dolphinemu.DolphinApplication;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public final class NetworkHelper {
    private NetworkHelper() {}

    public static int getNetworkIpAddress() {
        return GetNetworkIpAddress();
    }

    public static int getNetworkPrefixLength() {
        return GetNetworkPrefixLength();
    }

    public static int getNetworkGateway() {
        return GetNetworkGateway();
    }

    public static int GetNetworkIpAddress() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return 0;
        }

        LinkAddress link = getIPv4Link();
        if (link == null) {
            return 0;
        }

        return inetAddressToInt(link.getAddress());
    }

    public static int GetNetworkPrefixLength() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return 0;
        }

        LinkAddress link = getIPv4Link();
        if (link == null) {
            return 0;
        }

        return link.getPrefixLength();
    }

    public static int GetNetworkGateway() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return 0;
        }

        ConnectivityManager manager = getConnectivityManager();
        if (manager == null) {
            return 0;
        }

        Network activeNetwork = manager.getActiveNetwork();
        if (activeNetwork == null) {
            return 0;
        }

        LinkProperties properties = manager.getLinkProperties(activeNetwork);
        if (properties == null) {
            return 0;
        }

        try {
            InetAddress address = InetAddress.getByName("8.8.8.8");
            for (RouteInfo route : properties.getRoutes()) {
                if (route.matches(address)) {
                    InetAddress gateway = route.getGateway();
                    return gateway == null ? 0 : inetAddressToInt(gateway);
                }
            }
        } catch (UnknownHostException ignored) {
        }

        Log.warning("No valid gateway found.");
        return 0;
    }

    private static ConnectivityManager getConnectivityManager() {
        Context context = DolphinApplication.getAppContext();
        if (context == null) {
            return null;
        }

        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);
        if (manager == null) {
            Log.warning("Cannot get Network link as ConnectivityManager is null.");
        }
        return manager;
    }

    private static LinkAddress getIPv4Link() {
        ConnectivityManager manager = getConnectivityManager();
        if (manager == null) {
            return null;
        }

        Network activeNetwork = manager.getActiveNetwork();
        if (activeNetwork == null) {
            Log.warning("Active network is null.");
            return null;
        }

        LinkProperties properties = manager.getLinkProperties(activeNetwork);
        if (properties == null) {
            Log.warning("Link properties is null.");
            return null;
        }

        for (LinkAddress link : properties.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            if (address instanceof Inet4Address) {
                return link;
            }
        }

        Log.warning("No IPv4 link found.");
        return null;
    }

    private static int inetAddressToInt(InetAddress address) {
        byte[] bytes = address.getAddress();
        int result = 0;
        for (int i = 0; i < bytes.length; i++) {
            result |= (bytes[i] & 0xFF) << (8 * i);
        }
        return result;
    }
}
