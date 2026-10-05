package app.medicinecabinet.ui;

import android.content.ContentResolver;
import android.provider.Settings;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSettings;

/** 仅模拟设置的读取结果，不调用系统设置写入接口。 */
@Implements(Settings.Global.class)
public final class ReducedMotionSettingsShadow extends ShadowSettings.ShadowGlobal {
    @Implementation
    protected static float getFloat(ContentResolver resolver, String name, float defaultValue) {
        if (Settings.Global.ANIMATOR_DURATION_SCALE.equals(name)) return 0f;
        return ShadowSettings.ShadowGlobal.getFloat(resolver, name, defaultValue);
    }
}
