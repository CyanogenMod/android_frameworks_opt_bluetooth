/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.bluetooth.client.map;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.SparseArray;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.WeakReference;

import javax.obex.ServerSession;

class BluetoothMnsService {

    private static final String TAG = "BluetoothMnsService";

    private static final ParcelUuid MAP_MNS =
            ParcelUuid.fromString("00001133-0000-1000-8000-00805F9B34FB");

    static final int MSG_EVENT = 1;

    /* for BluetoothMasClient */
    static final int EVENT_REPORT = 1001;

    /* these are shared across instances */
    static private SparseArray<Handler> mCallbacks = null;
    static private RfcommSocketAcceptThread mRfcommAcceptThread = null;
    static private L2capSocketAcceptThread mL2capAcceptThread = null;
    static private Handler mSessionHandler = null;
    static private BluetoothServerSocket mRfcommServerSocket = null;
    static private BluetoothServerSocket mL2capServerSocket = null;
    static private ServerSession mMnsServerSession = null;
    private static final int SDP_MAP_MNS_VERSION       = 0x0102;
    private static final int SDP_MAP_MNS_FEATURES      = 0x0000007F;
    private int mMnsSdpHandle = -1;;

    private static class SessionHandler extends Handler {

        private final WeakReference<BluetoothMnsService> mService;

        SessionHandler(BluetoothMnsService service) {
            mService = new WeakReference<BluetoothMnsService>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "Handler: msg: " + msg.what);

            switch (msg.what) {
                case MSG_EVENT:
                    int instanceId = msg.arg1;

                    synchronized (mCallbacks) {
                        Handler cb = mCallbacks.get(instanceId);

                        if (cb != null) {
                            BluetoothMapEventReport ev = (BluetoothMapEventReport) msg.obj;
                            cb.obtainMessage(EVENT_REPORT, ev).sendToTarget();
                        } else {
                            Log.w(TAG, "Got event for instance which is not registered: "
                                    + instanceId);
                        }
                    }
                    break;
            }
        }
    }

    private static class RfcommSocketAcceptThread extends Thread {

        private boolean mInterrupted = false;

        @Override
        public void run() {

            while (!mInterrupted) {
                try {
                    Log.v(TAG, "waiting to accept connection on Rfcomm socket...");

                    BluetoothSocket sock = mRfcommServerSocket.accept();

                    Log.v(TAG, "new incoming connection from "
                            + sock.getRemoteDevice().getName() + " on rfcomm socket");

                    // session will live until closed by remote
                    BluetoothMnsObexServer srv = new BluetoothMnsObexServer(mSessionHandler);
                    BluetoothMapTransport transport = new BluetoothMapTransport(
                            sock, BluetoothSocket.TYPE_RFCOMM);
                    mMnsServerSession = new ServerSession(transport, srv, null);
                } catch (IOException ex) {
                    Log.v(TAG, "I/O exception when waiting to accept (aborted)");
                    mInterrupted = true;
                }
            }

            if (mRfcommServerSocket != null) {
                try {
                    mRfcommServerSocket.close();
                } catch (IOException e) {
                    Log.v(TAG, "I/O exception while closing rfcomm socket", e);
                }

                mRfcommServerSocket = null;
            }
        }
    }

    private static class L2capSocketAcceptThread extends Thread {

        private boolean mInterrupted = false;

        @Override
        public void run() {

            while (!mInterrupted) {
                try {
                    Log.v(TAG, "waiting to accept connection on l2cap socket...");

                    BluetoothSocket sock = mL2capServerSocket.accept();

                    Log.v(TAG, "new incoming connection from "
                            + sock.getRemoteDevice().getName() + " on l2cap socket");

                    // session will live until closed by remote
                    BluetoothMnsObexServer srv = new BluetoothMnsObexServer(mSessionHandler);
                    BluetoothMapTransport transport = new BluetoothMapTransport(
                            sock, BluetoothSocket.TYPE_L2CAP);
                    mMnsServerSession = new ServerSession(transport, srv, null);
                } catch (IOException ex) {
                    Log.v(TAG, "I/O exception when waiting to accept (aborted)");
                    mInterrupted = true;
                }
            }

            if (mL2capServerSocket != null) {
                try {
                    mL2capServerSocket.close();
                } catch (IOException e) {
                    Log.v(TAG, "I/O exception while closing l2cap socket", e);
                }

                mL2capServerSocket = null;
            }
        }
    }

    BluetoothMnsService() {
        Log.v(TAG, "BluetoothMnsService()");

        if (mCallbacks == null) {
            Log.v(TAG, "BluetoothMnsService(): allocating callbacks");
            mCallbacks = new SparseArray<Handler>();
        }

        if (mSessionHandler == null) {
            Log.v(TAG, "BluetoothMnsService(): allocating session handler");
            mSessionHandler = new SessionHandler(this);
        }
    }

    private void startServerSocketsListener() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        int socketChannel = BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP;
        boolean initSocketOK = true;
        String createSocketType = new String("rfcomm server socket");

        Log.v(TAG, "startServerSocketsListener: mRfcommServerSocket: " + mRfcommServerSocket
                + " mL2capServerSocket: " + mL2capServerSocket);

        try {
            if (mRfcommServerSocket == null) {
                mRfcommServerSocket = adapter.listenUsingRfcommOn(socketChannel);
            }

            createSocketType = "l2cap server socket";
            if (mL2capServerSocket == null) {
                 mL2capServerSocket = adapter.listenUsingL2capOn(socketChannel);
             }
        } catch (IOException e) {
                Log.e(TAG, "I/O exception when trying to create " + createSocketType, e);
                initSocketOK =false;
        }

        if (initSocketOK) {
            startAcceptThread();
            if(mMnsSdpHandle >= 0) {
                Log.d(TAG, "Removing Mns SDP record: " + mMnsSdpHandle);
                adapter.removeSdpRecord(mMnsSdpHandle);
                mMnsSdpHandle = -1;
            }
            mMnsSdpHandle = adapter.createMapMnsSdpRecord("MAP Message Notification Service",
                    mRfcommServerSocket.getChannel(), mL2capServerSocket.getChannel(),
                    SDP_MAP_MNS_VERSION, SDP_MAP_MNS_FEATURES);
        }
    }

    private void startAcceptThread() {
        Log.v(TAG, "startAcceptThread: mRfcommAcceptThread: " + mRfcommAcceptThread
                + "mL2capAcceptThread: " + mL2capAcceptThread);
        if (mRfcommAcceptThread == null) {
            mRfcommAcceptThread = new RfcommSocketAcceptThread();
            mRfcommAcceptThread.setName("BluetoothMnsRfcommAcceptThread");
            mRfcommAcceptThread.start();
        }

        if (mL2capAcceptThread == null) {
            mL2capAcceptThread = new L2capSocketAcceptThread();
            mL2capAcceptThread.setName("BluetoothMnsL2capAcceptThread");
            mL2capAcceptThread.start();
        }
    }

    public void closeServerSockets() {
        closeRfcommSocket();
        closeL2capSocket();
    }

    public void closeRfcommSocket()
    {
        Log.v(TAG, "closeRfcommSocket(): mRfcommServerSocket: " + mRfcommServerSocket
                + " mRfcommAcceptThread: " + mRfcommAcceptThread);
        if (mRfcommServerSocket != null) {
            try {
                mRfcommServerSocket.close();
            } catch (IOException e) {
                Log.v(TAG, "closeRfcommSocket(): " + e);
            }

            mRfcommServerSocket = null;
        }

        if (mRfcommAcceptThread != null) {

            mRfcommAcceptThread.interrupt();

            try {
                mRfcommAcceptThread.join(2000);
            } catch (InterruptedException e) {
                Log.v(TAG, "closeRfcommSocket(): " + e);
            }

            mRfcommAcceptThread = null;
        }
    }

    public void closeL2capSocket()
    {
        Log.v(TAG, "closeL2capSocket(): mL2capServerSocket: " + mL2capServerSocket
                + " mL2capAcceptThread: " + mL2capAcceptThread);
        if (mL2capServerSocket != null) {
            try {
                mL2capServerSocket.close();
            } catch (IOException e) {
                Log.v(TAG, "closeL2capSocket(): " + e);
            }

            mL2capServerSocket = null;
        }

        if (mL2capAcceptThread != null) {

            mL2capAcceptThread.interrupt();

            try {
                mL2capAcceptThread.join(2000);
            } catch (InterruptedException e) {
                Log.v(TAG, "closeL2capSocket(): " + e);
            }

            mL2capAcceptThread = null;
        }
    }

    public void registerCallback(int instanceId, Handler callback) {

        synchronized (mCallbacks) {
            Log.v(TAG, "registerCallback(): cb: " + mCallbacks.size());
            if (mCallbacks.size() == 0) {
                Log.v(TAG, "registerCallback(): starting MNS server");
                startServerSocketsListener();
            }
            mCallbacks.put(instanceId, callback);
        }
    }

    public void unregisterCallback(int instanceId) {

        synchronized (mCallbacks) {
            Log.v(TAG, "unregisterCallback(): instanceId: " + instanceId
                    + " cb: " + mCallbacks.size());
            mCallbacks.remove(instanceId);

            if (mCallbacks.size() == 0) {
                Log.v(TAG, "unregisterCallback(): shutting down MNS server: mMnsServerSession: "
                        + mMnsServerSession);

                if(mMnsSdpHandle >= 0) {
                    Log.d(TAG, "Removing Mns SDP record: " + mMnsSdpHandle);
                    BluetoothAdapter.getDefaultAdapter().removeSdpRecord(mMnsSdpHandle);
                    mMnsSdpHandle = -1;
                }
                closeServerSockets();
            }
        }
    }
}
