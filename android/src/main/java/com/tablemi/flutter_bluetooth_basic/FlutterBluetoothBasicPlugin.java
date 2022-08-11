package com.tablemi.flutter_bluetooth_basic;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

/**
 * FlutterBluetoothBasicPlugin
 */
public class FlutterBluetoothBasicPlugin implements FlutterPlugin, MethodCallHandler,
        RequestPermissionsResultListener, ActivityAware {
    private static final String TAG = "BluetoothBasicPlugin";
    private int id = 0;
    private ThreadPool threadPool;
    private static final String NAMESPACE = "flutter_bluetooth_basic";
    private MethodChannel channel;
    private EventChannel stateChannel;
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private FlutterPluginBinding pluginBinding;
    private ActivityPluginBinding activityBinding;
    private Context context;
    private int lastEventId = 1452;

    private static final int REQUEST_FINE_LOCATION_PERMISSIONS = 1451;
    private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1452;
    private static final int REQUEST_CONNECT_PERMISSIONS = 1453;
    private static final int REQUEST_ENABLE_BLUETOOTH = 1001;
    private static final int REQUEST_DEVICES = 1002;

    private MethodCall pendingCall;
    private Result pendingResult;
    private Map<String, Object> pendingArgs;

    private final Object tearDownLock = new Object();

    //private final Map<Integer, OperationOnPermission> operationsOnPermission = new HashMap<>();


    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        Log.d(TAG, "onAttachedToEngine");
        pluginBinding = flutterPluginBinding;
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), NAMESPACE + "/methods");
        channel.setMethodCallHandler(this);
        this.stateChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), NAMESPACE + "/state");
        stateChannel.setStreamHandler(stateStreamHandler);
        this.context = (Application) pluginBinding.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.mBluetoothManager = this.context.getSystemService(BluetoothManager.class);
        } else
            this.mBluetoothManager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.mBluetoothAdapter = mBluetoothManager.getAdapter();

    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        Log.d(TAG, "onDetachedFromEngine");
        pluginBinding = null;
        context = null;
        channel.setMethodCallHandler(null);
        channel = null;
        stateChannel.setStreamHandler(null);
        stateChannel = null;
        mBluetoothAdapter = null;
        mBluetoothManager = null;
    }


    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        Log.d(TAG, "onAttachedToActivity");
        activityBinding = binding;
        binding.addActivityResultListener(
                (requestCode, resultCode, data) -> {
                    switch (requestCode) {
                        case REQUEST_ENABLE_BLUETOOTH:
                            // @TODO - used underlying value of `Activity.RESULT_CANCELED`
                            //  since we tend to use `androidx` in which I were
                            //  not able to find the constant.
                            if (pendingResult != null) {
                                pendingResult.success(resultCode != 0);
                            }
                            return true;
                        default:
                            return false;
                    }
                }
        );
        activityBinding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "onDetachedFromActivityForConfigChanges");
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        Log.d(TAG, "onReattachedToActivityForConfigChanges");
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        Log.d(TAG, "onDetachedFromActivity");
        activityBinding.removeRequestPermissionsResultListener(this);
        activityBinding = null;
    }

   /* private interface OperationOnPermission {
        void op(boolean granted, String permission);
    }*/

/*
    private void ensurePermissionBeforeAction(String permission, OperationOnPermission operation) {
        if (permission != null &&
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            operationsOnPermission.put(lastEventId, (granted, perm) -> {
                operationsOnPermission.remove(lastEventId);
                operation.op(granted, perm);
            });
            ActivityCompat.requestPermissions(
                    activityBinding.getActivity(),
                    new String[]{permission},
                    lastEventId);
            lastEventId++;
        } else {
            operation.op(true, permission);
        }
    }
*/

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        if (mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
            result.error("bluetooth_unavailable", "Bluetooth is unavailable", null);
            return;
        }

        final Map<String, Object> args = call.arguments();

        switch (call.method) {
            case "state":
                state(result);
                break;
            case "isAvailable":
                result.success(mBluetoothAdapter != null);
                break;
            case "isOn":
            case "isEnabled":
                result.success(mBluetoothAdapter.isEnabled());
                break;
            case "isConnected":
                result.success(threadPool != null);
                break;
            case "requestEnable":
                if (!mBluetoothAdapter.isEnabled()) {
                    pendingResult = result;
                    Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    ActivityCompat.startActivityForResult(activityBinding.getActivity(), intent, REQUEST_ENABLE_BLUETOOTH, null);
                } else {
                    result.success(true);
                }
                break;

            case "requestDisable":
                if (mBluetoothAdapter.isEnabled()) {
                    mBluetoothAdapter.disable();
                    result.success(true);
                } else {
                    result.success(false);
                }
                break;
            case "startScan": {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(activityBinding.getActivity(),
                            Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(activityBinding.getActivity(),
                                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(activityBinding.getActivity(),
                                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                        ActivityCompat.requestPermissions(activityBinding.getActivity(), new String[]{
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.ACCESS_FINE_LOCATION,},
                                REQUEST_FINE_LOCATION_PERMISSIONS);
                        pendingCall = call;
                        pendingResult = result;
                        break;
                    }
                } else {
                    if (ContextCompat.checkSelfPermission(activityBinding.getActivity(),
                            Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(activityBinding.getActivity(),
                            Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                        ActivityCompat.requestPermissions(activityBinding.getActivity(),
                                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_COARSE_LOCATION_PERMISSIONS);
                        pendingCall = call;
                        pendingResult = result;
                        break;
                    }
                }
                startScan(call, result);
                break;
            }

            case "getBondedDevices":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(activityBinding.getActivity(),
                            Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                        ActivityCompat.requestPermissions(activityBinding.getActivity(), new String[]{
                                        Manifest.permission.ACCESS_FINE_LOCATION,},
                                REQUEST_DEVICES);
                        pendingResult = result;
                        break;
                    }
                } else {
                    if (ContextCompat.checkSelfPermission(activityBinding.getActivity(),
                            Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(activityBinding.getActivity(),
                            Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                        ActivityCompat.requestPermissions(activityBinding.getActivity(),
                                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_DEVICES);
                        pendingResult = result;
                        break;
                    }
                }
                getDevices(result);
                break;

            case "stopScan":
                stopScan();
                result.success(null);
                break;
            case "connect":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                            activityBinding.getActivity(),
                            new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                            REQUEST_CONNECT_PERMISSIONS);
                    pendingResult = result;
                    pendingArgs = args;
                    break;
                }
                connect(result, args);
                break;
            case "disconnect":
                result.success(disconnect());
                break;
            case "destroy":
                result.success(destroy());
                break;
            case "writeData":
                writeData(result, args);
                break;
            case "printReceipt":
                print(result, args);
                break;
            default:
                result.notImplemented();
                break;
        }

    }

    @SuppressWarnings("unchecked")
    private void print(Result result, Map<String, Object> args) {
        if (args.containsKey("config") && args.containsKey("data")) {
            final Map<String, Object> config = (Map<String, Object>) args.get("config");
            final List<Map<String, Object>> list = (List<Map<String, Object>>) args.get("data");
            if (list == null) {
                return;
            } else {
                threadPool = ThreadPool.getInstantiation();
                threadPool.addSerialTask(() -> {
                    Vector<Byte> vectorData = PrintContent.mapToReceipt(config, list);
                    DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].sendDataImmediately(vectorData);
                });
            }
        } else {
            result.error("please add config or data", "", null);
        }

    }


    static private boolean checkIsDeviceConnected(BluetoothDevice device) {
        try {
            java.lang.reflect.Method method;
            method = device.getClass().getMethod("isConnected");
            return (boolean) (Boolean) method.invoke(device);
        } catch (Exception ex) {
            return false;
        }
    }

    private void getDevices(Result result) {
        List<Map<String, Object>> devices = new ArrayList<>();
        for (BluetoothDevice device : mBluetoothAdapter.getBondedDevices()) {
            Map<String, Object> ret = new HashMap<>();
            ret.put("address", device.getAddress());
            ret.put("name", device.getName());
            ret.put("type", device.getType());
            ret.put("isConnected", checkIsDeviceConnected(device));
            devices.add(ret);
        }

        result.success(devices);
    }

    private void state(Result result) {
        try {
            switch (mBluetoothAdapter.getState()) {
                case BluetoothAdapter.STATE_OFF:
                    result.success(BluetoothAdapter.STATE_OFF);
                    break;
                case BluetoothAdapter.STATE_ON:
                    result.success(BluetoothAdapter.STATE_ON);
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    result.success(BluetoothAdapter.STATE_TURNING_OFF);
                    break;
                case BluetoothAdapter.STATE_TURNING_ON:
                    result.success(BluetoothAdapter.STATE_TURNING_ON);
                    break;
                default:
                    result.success(0);
                    break;
            }
        } catch (SecurityException e) {
            result.error("invalid_argument", "Argument 'address' not found", null);
        }

    }

    private void startScan(MethodCall call, Result result) {
        Log.d(TAG, "start scan ");

        try {
            startScan();
            result.success(null);
        } catch (Exception e) {
            result.error("startScan", e.getMessage(), null);
        }
    }

    private void invokeMethodUIThread(final String name, final BluetoothDevice device) {
        final Map<String, Object> ret = new HashMap<>();
        ret.put("address", device.getAddress());
        ret.put("name", device.getName());
        ret.put("type", device.getType());
        ret.put("isConnected", checkIsDeviceConnected(device));

        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (tearDownLock) {
                //Could already be teared down at this moment
                if (channel != null) {
                    channel.invokeMethod(name, ret);
                } else {
                    Log.w(TAG, "Tried to call " + name + " on closed channel");
                }
            }
        });
    }

    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device != null && device.getName() != null) {
                invokeMethodUIThread("ScanResult", device);
            }
        }
    };

    private void startScan() throws IllegalStateException {
        BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null)
            throw new IllegalStateException("getBluetoothLeScanner() is null. Is the Adapter on?");

        // 0:lowPower 1:balanced 2:lowLatency -1:opportunistic
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, settings, mScanCallback);
    }

    private void stopScan() {
        BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (scanner != null) scanner.stopScan(mScanCallback);
    }

    static private String exceptionToString(Exception ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        return sw.toString();
    }

    private void connect(Result result, Map<String, Object> args) {
        if (args.containsKey("address")) {
            String address = (String) args.get("address");

            try {
                if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                    throw new ClassCastException();
                }
            } catch (ClassCastException ex) {
                result.error("invalid_argument", "'address' argument is required to be string containing remote MAC address", null);
                return;
            }
            new DeviceConnFactoryManager.Build()
                    .setId(id)
                    // Set the connection method
                    .setConnMethod(DeviceConnFactoryManager.CONN_METHOD.BLUETOOTH)
                    // Set the connected Bluetooth mac address
                    .setMacAddress(address)
                    .build();
            // Open port
            threadPool = ThreadPool.getInstantiation();
            threadPool.addSerialTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].openPort();
                        if (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].getConnState())
                            result.success(true);
                    } catch (Exception ex) {
                        result.error("connect_error", ex.getMessage(), exceptionToString(ex));
                    }
                }
            });


        } else {
            result.error("invalid_argument", "Argument 'address' not found", null);
        }

    }

    /**
     * Reconnect to recycle the last connected object to avoid memory leaks
     */
    private boolean disconnect() {

        if (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id] != null && DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort != null
                && DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].getConnState()) {
            DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].reader.cancel();
            DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort.closePort();
            DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort = null;
        }
        return true;
    }

    private boolean destroy() {
        DeviceConnFactoryManager.closeAllPort();
        if (threadPool != null) {
            threadPool.stopThreadPool();
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    private void writeData(Result result, Map<String, Object> args) {
        if (args.containsKey("bytes")) {
            final ArrayList<Integer> bytes = (ArrayList<Integer>) args.get("bytes");

            threadPool = ThreadPool.getInstantiation();
            threadPool.addSerialTask(new Runnable() {
                @Override
                public void run() {
                    Vector<Byte> vectorData = new Vector<>();
                    for (int i = 0; i < bytes.size(); ++i) {
                        Integer val = bytes.get(i);
                        vectorData.add(Byte.valueOf(Integer.toString(val > 127 ? val - 256 : val)));
                    }

                    DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].sendDataImmediately(vectorData);
                }
            });
        } else {
            result.error("bytes_empty", "Bytes param is empty", null);
        }
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {

      /*  OperationOnPermission operation = operationsOnPermission.get(requestCode);
        if (operation != null && grantResults.length > 0) {
            operation.op(grantResults[0] == PackageManager.PERMISSION_GRANTED, permissions[0]);
            return true;
        }
        return false;*/

        if (requestCode == REQUEST_FINE_LOCATION_PERMISSIONS) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan(pendingCall, pendingResult);
            } else {
                pendingResult.error("no_permissions", "this plugin requires location permissions for scanning", null);
                pendingResult = null;
            }
            return true;
        } else if (requestCode == REQUEST_COARSE_LOCATION_PERMISSIONS) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan(pendingCall, pendingResult);
            } else {
                pendingResult.error("no_permissions", "this plugin requires location permissions for scanning", null);
                pendingResult = null;
            }
            return true;
        } else if (requestCode == REQUEST_CONNECT_PERMISSIONS) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                connect(pendingResult, pendingArgs);
            } else {
                pendingResult.error("no_permissions", "this plugin requires  permissions for connecting", null);
                pendingResult = null;
            }
            return true;
        } else if (requestCode == REQUEST_DEVICES) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getDevices(pendingResult);
            } else {
                pendingResult.error("no_permissions", "this plugin requires  permissions for getting devices", null);
                pendingResult = null;
            }
            return true;
        }


        return false;
    }

    private final StreamHandler stateStreamHandler = new StreamHandler() {
        private EventSink sink;

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                Log.d(TAG, "stateStreamHandler, current action: " + action);

                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    threadPool = null;
                    sink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    sink.success(1);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    threadPool = null;
                    sink.success(0);
                }
            }
        };

        @Override
        public void onListen(Object o, EventSink eventSink) {
            sink = eventSink;
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            context.registerReceiver(mReceiver, filter);
        }

        @Override
        public void onCancel(Object o) {
            sink = null;
            context.unregisterReceiver(mReceiver);
        }
    };


}
