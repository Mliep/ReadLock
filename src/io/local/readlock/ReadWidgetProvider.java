package io.local.readlock;

import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class ReadWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, android.appwidget.AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // 主更新由服务 tick 驱动；这里兜底（添加小组件/系统重建时）用当前缓存刷新
        ReadLockService.pushWidget(context, ReadLockService.getCachedMinutes(), ReadLockService.isLockedNow());
    }
}
