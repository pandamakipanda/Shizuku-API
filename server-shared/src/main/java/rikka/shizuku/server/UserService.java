package rikka.shizuku.server;

import android.app.ActivityThread;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextHidden;
import android.content.pm.ApplicationInfo;
import android.ddm.DdmHandleAppName;
import android.os.Build;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserHandleHidden;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dev.rikka.tools.refine.Refine;

public class UserService {

    private static String TAG;

    public static void setTag(String tag) {
        UserService.TAG = tag;
    }

    @Nullable
    public static Pair<IBinder, String> create(String[] args) {
        String name = null;
        String token = null;
        String pkg = null;
        String cls = null;
        int uid = -1;

        for (String arg : args) {
            if (arg.startsWith("--debug-name=")) {
                name = arg.substring(13);
            } else if (arg.startsWith("--token=")) {
                token = arg.substring(8);
            } else if (arg.startsWith("--package=")) {
                pkg = arg.substring(10);
            } else if (arg.startsWith("--class=")) {
                cls = arg.substring(8);
            } else if (arg.startsWith("--uid=")) {
                uid = Integer.parseInt(arg.substring(6));
            }
        }

        int userId = uid / 100000;

        Log.i(TAG, String.format("starting service %s/%s...", pkg, cls));

        IBinder service;

        try {
            ActivityThread activityThread = ActivityThread.systemMain();
            Context systemContext = activityThread.getSystemContext();

            DdmHandleAppName.setAppName(name != null ? name : pkg + ":user_service", userId);

            //noinspection InstantiationOfUtilityClass
            UserHandle userHandle = Refine.unsafeCast(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? UserHandleHidden.of(userId)
                            : new UserHandleHidden(userId));
            Context context = Refine.<ContextHidden>unsafeCast(systemContext).createPackageContextAsUser(pkg, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, userHandle);
            Field mPackageInfo = context.getClass().getDeclaredField("mPackageInfo");
            mPackageInfo.setAccessible(true);
            Object loadedApk = mPackageInfo.get(context);

            // Some OEM ROMs (MediaTek, Xiaomi, Transsion) patch LoadedApk.makeApplicationInner()
            // to call Application.getProcessName().equals(mPackageName). Application.getProcessName()
            // reads ActivityThread.currentProcessName(), which is
            //   (am != null && am.mBoundApplication != null) ? am.mBoundApplication.processName : null
            // This helper process is started by app_process and never calls bindApplication(), so
            // mBoundApplication is null, getProcessName() returns null, and that equals() throws a
            // NullPointerException. Populate mBoundApplication before makeApplication() runs.
            try {
                Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");

                Constructor<?> appBindDataConstructor = appBindDataClass.getDeclaredConstructor();
                appBindDataConstructor.setAccessible(true);
                Object appBindData = appBindDataConstructor.newInstance();

                Field processNameField = appBindDataClass.getDeclaredField("processName");
                processNameField.setAccessible(true);
                processNameField.set(appBindData, name != null ? name : pkg + ":user_service");

                // ActivityThread.currentPackageName() reads mBoundApplication.appInfo.packageName, so
                // appInfo must not be null either, otherwise this only relocates the NPE. An empty
                // ApplicationInfo keeps currentPackageName() returning null exactly as it did before,
                // when mBoundApplication itself was null.
                Field appInfoField = appBindDataClass.getDeclaredField("appInfo");
                appInfoField.setAccessible(true);
                appInfoField.set(appBindData, new ApplicationInfo());

                Field boundApplication = ActivityThread.class.getDeclaredField("mBoundApplication");
                boundApplication.setAccessible(true);
                boundApplication.set(activityThread, appBindData);
            } catch (Throwable ignored) {
            }

            Method makeApplication = loadedApk.getClass().getDeclaredMethod("makeApplication", boolean.class, Instrumentation.class);
            Application application = (Application) makeApplication.invoke(loadedApk, true, null);
            Field mInitialApplication = activityThread.getClass().getDeclaredField("mInitialApplication");
            mInitialApplication.setAccessible(true);
            mInitialApplication.set(activityThread, application);

            ClassLoader classLoader = application.getClassLoader();
            Class<?> serviceClass = classLoader.loadClass(cls);
            Constructor<?> constructorWithContext = null;
            try {
                constructorWithContext = serviceClass.getConstructor(Context.class);
            } catch (NoSuchMethodException | SecurityException ignored) {
            }
            if (constructorWithContext != null) {
                service = (IBinder) constructorWithContext.newInstance(application);
            } else {
                service = (IBinder) serviceClass.newInstance();
            }
        } catch (Throwable tr) {
            Log.w(TAG, String.format("unable to start service %s/%s...", pkg, cls), tr);
            return null;
        }

        return new Pair<>(service, token);
    }
}
