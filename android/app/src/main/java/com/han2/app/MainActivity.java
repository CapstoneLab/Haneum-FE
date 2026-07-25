package com.han2.app;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(ScreenCapturePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
