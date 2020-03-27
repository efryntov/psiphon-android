/*
 * Copyright (c) 2019, Psiphon Inc.
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

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * A set of utils for exchanging server connection info between devices
 */
public class ConnectionInfoExchangeUtils {

    // The mime type to be used for psiphon NFC exchanges
    public static final String NFC_MIME_TYPE = "psiphon://nfc";

    // The charset to use when encoding and decoding connection info payloads for NFC exchange
    private static final String NFC_EXCHANGE_CHARSET = Charset.defaultCharset().name();


    /**
     * @param intent an intent to check
     * @return true if the intents action is for NFC discovered; otherwise false
     */
    public static boolean isNfcDiscoveredIntent(Intent intent) {
        if (intent == null) {
            return false;
        }

        // ACTION_NDEF_DISCOVERED wasn't added until SDK 10
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD_MR1) {
            return NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction());
        }

        return false;
    }

    /**
     * Convert the connection info from bytes to a string.
     * Useful for NFC exchanges.
     *
     * @param connectionInfo the connectionInfo to be converted back to a string
     * @return connectionInfo encoded as US-ASCII; "" if unable to encode
     */
    public static String encodeConnectionInfo(byte[] connectionInfo) {
        try {
            // Try to encode the raw bytes
            return new String(connectionInfo, ConnectionInfoExchangeUtils.NFC_EXCHANGE_CHARSET);
        } catch (UnsupportedEncodingException e) {
            Utils.MyLog.d("unable to get connection info string", e);
            return "";
        }
    }

    /**
     * Convert the connection info from a string to bytes.
     * Useful for NFC exchanges.
     *
     * @param connectionInfo the connectionInfo to be converted to raw bytes
     * @return connectionInfo decoded as US-ASCII; {} if unable to decode
     */
    public static byte[] decodeConnectionInfo(String connectionInfo) {
        try {
            // Try to decode the string
            return connectionInfo.getBytes(ConnectionInfoExchangeUtils.NFC_EXCHANGE_CHARSET);
        } catch (UnsupportedEncodingException e) {
            Utils.MyLog.d("unable to get connection info bytes", e);
            return new byte[]{};
        }
    }

    /**
     * Gets the connection info payload string from an NFC bump intent
     *
     * @param intent an intent created by a NFC bump to exchange server connection info
     * @return the connection info string encoded in the intent; "" if unable to retrieve properly
     */
    public static String getConnectionInfoPayloadFromNfcIntent(Intent intent) {
        if (intent == null) {
            return "";
        }

        Parcelable[] messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);

        // Only one message should have been sent during NFC beam so retrieve that
        NdefMessage msg = (NdefMessage) messages[0];
        byte[] payload = msg.getRecords()[0].getPayload();
        return encodeConnectionInfo(payload);
    }

    /**
     * @return true if NFC HCE and reader mode are supported by the android version
     */
    public static Boolean isNfcSupported(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    public static void setNfcReaderMode(Activity activity, NfcAdapter nfcAdapter, boolean readerMode) {
        if (readerMode) {
            Bundle options = new Bundle();
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000);
            int flags = NfcAdapter.FLAG_READER_NFC_A;
            flags |= NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;

            nfcAdapter.enableReaderMode(activity, tag -> {
                        Log.d("HACK", "tag discovered: " + tag);
                        IsoDep isoDep = IsoDep.get(tag);
                        if (isoDep == null) {
                            Log.d("HACK", "IsoDep tag is null");
                            return;
                        }
                        try {
                            Log.d("HACK", "connect tag");
                            isoDep.connect();
                            byte[] resp = isoDep.transceive(NfcCardEmulationService.SELECT_AID);
                            String response = ConnectionInfoExchangeUtils.bytesToHex(resp);
                            Log.d("HACK", "tag response: " + response);
                            Log.d("HACK", "tag response length: " + resp.length);
//                            isoDep.close();
                        } catch (IOException e) {
                            Log.d("HACK", "connect tag error: " + e);
                        }
                    },
                    flags,
                    options
            );
            Log.d("HACK", "NFC reader only mode: enabled");
        } else {
            nfcAdapter.disableReaderMode(activity);
            Log.d("HACK", "NFC reader only mode: disabled");
        }
        PackageManager packageManager = activity.getApplicationContext().getPackageManager();
        ComponentName componentName = new ComponentName(activity.getApplicationContext().getPackageName(),
                NfcCardEmulationService.class.getName());
        packageManager.setComponentEnabledSetting(componentName,
                readerMode ?
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED :
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        Log.d("HACK", "NFC HCE service: " + (readerMode ? "disabled" : "enabled"));
    }
}
