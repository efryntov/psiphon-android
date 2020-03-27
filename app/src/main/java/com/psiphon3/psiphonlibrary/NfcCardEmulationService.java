package com.psiphon3.psiphonlibrary;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.nfc.cardemulation.HostApduService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.annotation.RequiresApi;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;

@RequiresApi(api = Build.VERSION_CODES.KITKAT)
public class NfcCardEmulationService extends HostApduService {
    private final Messenger incomingMessenger = new Messenger(new IncomingMessageHandler(this));

    public static byte[] SELECT_AID = {
            (byte) 0x00, // CLA Class
            (byte) 0xA4, // INS Instruction
            (byte) 0x04, // P1  Parameter 1
            (byte) 0x00, // P2  Parameter 2
            (byte) 0x0A, // Length of AID
            (byte) 0x50, // AID ("PsiphonNfc" hexed)
            (byte) 0x73,
            (byte) 0x69,
            (byte) 0x70,
            (byte) 0x68,
            (byte) 0x6f,
            (byte) 0x6e,
            (byte) 0x4e,
            (byte) 0x66,
            (byte) 0x63
    };

    private byte[] RESPONSE_OK = {
            (byte) 0x90,
            (byte) 0x00
    };

    private byte[] RESPONSE_ERROR = {
            (byte) 0x6A,
            (byte) 0x82
    };

    @Override
    public byte[] processCommandApdu(byte[] bytes, Bundle bundle) {
        Log.d("HACK", "processCommandApdu: " + ConnectionInfoExchangeUtils.bytesToHex(bytes));
        // TODO: start async processing of the data and use sendResponseApdu?

        if(Arrays.equals(SELECT_AID, bytes)) {
            bindTunnelServiceAndExportConnectionInfo();
            return null;
        }
        return RESPONSE_ERROR;
    }

    private void bindTunnelServiceAndExportConnectionInfo() {
        Context context = getApplicationContext();
        Intent bomIntent = new Intent(context, TunnelService.class);
        Intent vpnIntent = new Intent(context, TunnelVpnService.class);
        Rx2ServiceBindingFactory serviceBindingFactoryBom = new Rx2ServiceBindingFactory(context, bomIntent);
        Rx2ServiceBindingFactory serviceBindingFactoryVpn = new Rx2ServiceBindingFactory(context, vpnIntent);

        Observable<Messenger> serviceMessengerObservable =
                Observable.merge(serviceBindingFactoryBom.getMessengerObservable(),
                        serviceBindingFactoryVpn.getMessengerObservable());

        serviceMessengerObservable
                .firstOrError()
                .doOnSuccess(messenger -> {
                    try {
                        Message msg = Message.obtain(null, TunnelManager.ClientToServiceMessage.NFC_CONNECTION_INFO_EXCHANGE_EXPORT.ordinal());
                        msg.replyTo = incomingMessenger;
                        messenger.send(msg);
                    } catch (RemoteException e) {
                        Utils.MyLog.g(String.format("send message failed: %s", e.getMessage()));
                    }
                })
                .doOnError(err -> {
                    Log.d("HACK", "bindTunnelServiceAndExportConnectionInfo: tunnel is not running: " + err);
                    sendResponseApdu(RESPONSE_OK);
                })
                .ignoreElement()
                .onErrorComplete()
                .subscribe();
    }

    @Override
    public void onDeactivated(int i) {
    }

    private static class Rx2ServiceBindingFactory {
        private final Observable<Messenger> messengerObservable;
        private ServiceConnection serviceConnection;

        Rx2ServiceBindingFactory(Context context, Intent intent) {
            this.messengerObservable = Observable.using(Connection::new,
                    (final Connection<Messenger> con) -> {
                        serviceConnection = con;
                        context.bindService(intent, con, 0);
                        return Observable.create(con);
                    },
                    __ -> unbind(context))
                    .timeout(
                            Observable.timer(500, TimeUnit.MILLISECONDS),
                            ignored -> Observable.never()
                    )
                    .replay(1)
                    .refCount();
        }

        Observable<Messenger> getMessengerObservable() {
            return messengerObservable;
        }

        void unbind(Context context) {
            if (serviceConnection != null) {
                try {
                    context.unbindService(serviceConnection);
                    serviceConnection = null;
                } catch (java.lang.IllegalArgumentException e) {
                    // Ignore
                    // "java.lang.IllegalArgumentException: Service not registered"
                }
            }
        }

        private static class Connection<B extends Messenger> implements ServiceConnection, ObservableOnSubscribe<B> {
            private ObservableEmitter<? super B> subscriber;

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (subscriber != null && !subscriber.isDisposed() && service != null) {
                    //noinspection unchecked - we trust this one
                    subscriber.onNext((B) new Messenger(service));
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (subscriber != null && !subscriber.isDisposed()) {
                    subscriber.onComplete();
                }
            }

            @Override
            public void subscribe(ObservableEmitter<B> observableEmitter) throws Exception {
                this.subscriber = observableEmitter;
            }
        }
    }

    private static class IncomingMessageHandler extends Handler {
        private final WeakReference<NfcCardEmulationService> weakReference;
        public IncomingMessageHandler(NfcCardEmulationService service) {
            this.weakReference = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            if(msg.what == TunnelManager.ServiceToClientMessage.NFC_CONNECTION_INFO_EXCHANGE_RESPONSE_EXPORT.ordinal()) {
                Bundle data = msg.getData();
                String payload = data.getString(TunnelManager.DATA_NFC_CONNECTION_INFO_EXCHANGE_RESPONSE_EXPORT);
                Log.d("HACK", "Got payload from tunnel service: " + payload);
                NfcCardEmulationService service = weakReference.get();
                if (service != null && payload != null) {
                    service.sendResponseApdu(payload.getBytes());
                }
            } else {
                super.handleMessage(msg);
            }
        }
    }
}
