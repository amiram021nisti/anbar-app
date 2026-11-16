package ir.anbar.app;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.camera.CameraSettings;

/** اسکنر پشتیبان با فوکوس خودکار پیوسته و تنظیمات بهتر برای بارکد */
public class ScanActivity extends CaptureActivity {
    @Override
    protected DecoratedBarcodeView initializeContent() {
        DecoratedBarcodeView v = super.initializeContent();
        CameraSettings cs = v.getBarcodeView().getCameraSettings();
        cs.setAutoFocusEnabled(true);
        cs.setContinuousFocusEnabled(true);
        cs.setFocusMode(CameraSettings.FocusMode.CONTINUOUS);
        cs.setExposureEnabled(true);
        cs.setMeteringEnabled(true);
        cs.setBarcodeSceneModeEnabled(true);
        v.getBarcodeView().setCameraSettings(cs);
        return v;
    }
}
