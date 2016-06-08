/*
 * Copyright (c) 2016, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import com.psiphon3.R;

import net.grandcentrix.tray.AppPreferences;

import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;

import java.net.InetAddress;
import java.net.UnknownHostException;

public  class UpstreamProxySettings {
    public static synchronized boolean getUseHTTPProxy(Context context)
    {
        return new AppPreferences(context).getBoolean(context.getString(R.string.useProxySettingsPreference), false);
    }

    public static synchronized  boolean getUseSystemProxySettings(Context context)
    {
        return new AppPreferences(context).getBoolean(context.getString(R.string.useSystemProxySettingsPreference), false);
    }

    public static synchronized boolean getUseCustomProxySettings(Context context)
    {
        return new AppPreferences(context).getBoolean(context.getString(R.string.useCustomProxySettingsPreference), false);
    }

    public static synchronized String getCustomProxyHost(Context context)
    {
        return new AppPreferences(context).getString(context.getString(R.string.useCustomProxySettingsHostPreference), "");
    }

    public static synchronized String getCustomProxyPort(Context context)
    {
        return new AppPreferences(context).getString(context.getString(R.string.useCustomProxySettingsPortPreference), "");
    }

    public static synchronized boolean getUseProxyAuthentication(Context context)
    {
        return new AppPreferences(context).getBoolean(context.getString(R.string.useProxyAuthenticationPreference), false);
    }

    public static synchronized String getProxyUsername(Context context)
    {
        return new AppPreferences(context).getString(context.getString(R.string.useProxyUsernamePreference), "");
    }

    public static synchronized String getProxyPassword(Context context)
    {
        return new AppPreferences(context).getString(context.getString(R.string.useProxyPasswordPreference), "");
    }

    public static synchronized String getProxyDomain(Context context)
    {
        return new AppPreferences(context).getString(context.getString(R.string.useProxyDomainPreference), "");
    }

    public static class ProxySettings
    {
        public String proxyHost;
        public int proxyPort;
    }

    // Call this before doing anything that could change the system proxy settings
    // (such as setting a WebView's proxy)
    public synchronized static void saveSystemProxySettings(Context context)
    {
        ProxySettings settings = getSavedSystemProxySettings(context);

        AppPreferences pref = new AppPreferences(context);
        if (settings == null)
        {
            settings = getSystemProxySettings(context);
            pref.put(context.getString(R.string.savedSystemProxyHost), settings.proxyHost);
            pref.put(context.getString(R.string.savedSystemProxyHost), String.valueOf(settings.proxyPort));
        }
    }

    public synchronized static ProxySettings getSavedSystemProxySettings(Context context) {
        AppPreferences pref = new AppPreferences(context);
        ProxySettings settings = new ProxySettings();

        settings.proxyHost = pref.getString(context.getString(R.string.savedSystemProxyHost), "");
        String port = pref.getString(context.getString(R.string.savedSystemProxyPort), "");
        settings.proxyPort = Integer.parseInt(port != null ? port : "-1");

        if (TextUtils.isEmpty(settings.proxyHost) ||
                settings.proxyPort <= 0)
        {
            settings = null;
        }
        return  settings;
    }

    // Checks if we are supposed to use proxy settings, custom or system,
    // and if system, if any system proxy settings are configured.
    // Returns the user-requested proxy settings.
    public synchronized static ProxySettings getProxySettings(Context context)
    {
        if (!getUseHTTPProxy(context))
        {
            return null;
        }

        ProxySettings settings = null;

        if (getUseCustomProxySettings(context))
        {
            settings = new ProxySettings();

            settings.proxyHost = getCustomProxyHost(context);
            String port = getCustomProxyPort(context);
            try
            {
                settings.proxyPort = Integer.parseInt(port);
            }
            catch (NumberFormatException e)
            {
                settings.proxyPort = -1;
            }
        }

        if (getUseSystemProxySettings(context))
        {
            settings = getSystemProxySettings(context);
        }

        return settings;
    }

    public synchronized static Credentials getProxyCredentials(Context context) {
        if (!getUseProxyAuthentication(context)) {
            return null;
        }

        String username = getProxyUsername(context);
        String password = getProxyPassword(context);
        String domain = getProxyDomain(context);

        if (username == null || username.trim().equals("")) {
            return null;
        }
        if (password == null || password.trim().equals("")) {
            return null;
        }
        if (domain == null || domain.trim().equals("")) {
            return new NTCredentials(username, password, "", "");
        }

        String localHost;
        try {
            localHost = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            localHost = "localhost";
        }

        return new NTCredentials(username, password, localHost, domain);
    }

    private static ProxySettings getSystemProxySettings(Context context)
    {
        ProxySettings settings = getSavedSystemProxySettings(context);

        if (settings == null)
        {
            settings = new ProxySettings();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
            {
                settings.proxyHost = System.getProperty("http.proxyHost");
                String port = System.getProperty("http.proxyPort");
                settings.proxyPort = Integer.parseInt(port != null ? port : "-1");
            }
            else
            {
                settings.proxyHost = android.net.Proxy.getHost(context);
                settings.proxyPort = android.net.Proxy.getPort(context);
            }

            if (TextUtils.isEmpty(settings.proxyHost) ||
                    settings.proxyPort <= 0)
            {
                settings = null;
            }
        }

        return settings;
    }

    // Returns a tunnel-core compatible proxy URL for the
    // current user configured proxy settings.
    // e.g., http://NTDOMAIN\NTUser:password@proxyhost:3375,
    //       http://user:password@proxyhost:8080", etc.
    public synchronized static String getUpstreamProxyUrl(Context context)
    {
        ProxySettings proxySettings = getProxySettings(context);

        if (proxySettings == null)
        {
            return "";
        }

        StringBuilder url = new StringBuilder();
        url.append("http://");

        NTCredentials credentials = (NTCredentials) getProxyCredentials(context);

        if (credentials != null)
        {
            if (!credentials.getDomain().equals(""))
            {
                url.append(credentials.getDomain());
                url.append("\\");
            }
            url.append(credentials.getUserName());
            url.append(":");
            url.append(credentials.getPassword());
            url.append("@");
        }

        url.append(proxySettings.proxyHost);
        url.append(":");
        url.append(proxySettings.proxyPort);

        return url.toString();
    }
}
